package com.example.bonsaiagent.llm

import android.util.Log

/**
 * JNI 封装：对接 PrismML fork 编译出的 libllama / libggml（经 bonsai_llm.so）。
 * 生成是同步阻塞的，但会通过 Callback 流式回传每个 piece（打字机效果）。
 * 调用方务必在后台线程执行 generate，并把 onPiece 的结果切回主线程更新 UI。
 */
object Llama {
    private const val TAG = "BonsaiLlama"

    init {
        System.loadLibrary("bonsai_llm")
    }

    private var handle: Long = 0L
    private var pieceSink: ((String) -> Unit)? = null

    // v10.118：第二个独立模型（1B 小模型，专做网页总结）。
    // native 的 generate() 收 handle 参数，天生支持多实例；全局变量每次 generate 前会重置，
    // 只要不与主模型同时生成就安全（网页总结时对话不会进行）。零 C++ 改动。
    private var fastHandle: Long = 0L

    interface Callback {
        fun onToken(piece: String)
    }

    private external fun init(modelPath: String, nThreads: Int, nCtx: Int, nGpuLayers: Int): Long

    /**
     * v4.0：告诉 native 层 CPU 后端 .so 所在目录。
     * 开了 GGML_BACKEND_DL 之后，ggml 会在运行时 dlopen 当前 CPU 支持的那份
     * libggml-cpu-android_armvX_*.so；Android 上它自己找不到这个目录，必须显式指定。
     */
    private external fun setBackendPath(dir: String)
    private external fun lastError(): String
    private external fun setCallback(cb: Callback)

    /** 最近一次原生错误详情（加载失败时给界面显示真实原因） */
    fun getLastError(): String = try { lastError() } catch (e: Throwable) { "" }
    private external fun generate(handle: Long, sysPrefix: String, prompt: String,
                                  temp: Float, topK: Int, topP: Float, repPen: Float,
                                  maxTokens: Int): String
    private external fun freeNative(handle: Long)

    // v10.72：挂草稿模型（投机解码）。path 传 null/空/"none" = 关闭。
    private external fun initDraft(handle: Long, path: String, nThreads: Int, maxDraft: Int): Boolean

    // v10.94：n-gram 自投机（不需要第二个模型，零额外内存）
    private external fun initNgram(handle: Long, enable: Boolean, n: Int, gamma: Int): Boolean

    /** v10.27：把任意路径丢给原生 dlopen，返回 size + 成败 + 真实 dlerror */
    private external fun dlopenProbe(path: String): String

    fun probeDlopen(path: String): String = try { dlopenProbe(path) } catch (t: Throwable) {
        "JNI 异常: ${t.javaClass.simpleName} ${t.message}"
    }

    fun isLoaded(): Boolean = handle != 0L

    /** 加载 .gguf 模型；threads 建议 = CPU 大核数，nCtx = 上下文窗口 */
    fun load(modelPath: String, threads: Int, nCtx: Int, backendDir: String? = null): Boolean {
        if (handle != 0L) unload()
        if (!backendDir.isNullOrBlank()) {
            try { setBackendPath(backendDir) } catch (e: Throwable) {
                Log.w(TAG, "设置后端目录失败: ${e.message}")
            }
        }
        val h = init(modelPath, threads, nCtx, 0)
        if (h == 0L) {
            Log.e(TAG, "模型加载失败: $modelPath")
            return false
        }
        handle = h
        setCallback(object : Callback {
            override fun onToken(piece: String) {
                pieceSink?.invoke(piece)
            }
        })
        Log.i(TAG, "模型已加载")
        return true
    }

    /**
     * 同步生成完整文本，过程中通过 onPiece 流式回调。
     * 注意：本函数会阻塞调用线程直到生成结束。
     */
    /**
     * @param sysPrefix 固定前缀（system prompt 部分）。非空时会被 KV 缓存复用：
     *                  第一次算完存快照，之后每轮直接恢复，省掉重复的 prefill。
     *                  传空字符串就是旧行为（整段 prompt 一起算）。
     */
    fun generate(sysPrefix: String, prompt: String, temp: Float, topK: Int, topP: Float,
                 repPen: Float, maxTokens: Int, onPiece: (String) -> Unit): String {
        pieceSink = onPiece
        return generate(handle, sysPrefix, prompt, temp, topK, topP, repPen, maxTokens)
    }

    fun unload() {
        if (handle != 0L) {
            freeNative(handle)
            handle = 0L
        }
    }

    // ---- v10.118：第二个独立模型（1B 网页总结专用）----

    /** 加载小模型；返回是否成功。nCtx 用小值（512）省内存 */
    fun loadFast(modelPath: String, threads: Int, nCtx: Int, backendDir: String? = null): Boolean {
        unloadFast()
        if (backendDir != null) setBackendPath(backendDir)
        fastHandle = try {
            init(modelPath, threads, nCtx, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "loadFast failed", t)
            0L
        }
        return fastHandle != 0L
    }

    fun isFastLoaded(): Boolean = fastHandle != 0L

    /** 用快速模型生成（复用同一套 native generate，只传不同的 handle） */
    fun generateFast(sysPrefix: String, prompt: String, temp: Float, topK: Int, topP: Float,
                     repPen: Float, maxTokens: Int, onPiece: (String) -> Unit): String {
        pieceSink = onPiece
        return generate(fastHandle, sysPrefix, prompt, temp, topK, topP, repPen, maxTokens)
    }

    fun unloadFast() {
        if (fastHandle != 0L) {
            freeNative(fastHandle)
            fastHandle = 0L
        }
    }

    /**
     * v10.72 投机解码：把草稿模型挂到已加载的主模型上。
     *
     * 加速原理：手机推理卡在内存带宽 —— 每生成 1 个 token 就要把权重读一遍。
     * 投机解码让 0.6B 的草稿模型一次猜 γ 个，主模型**只读一遍权重**就把这 γ 个
     * 全部验证掉，猜对的直接采纳。每个被采纳的 token 都是白赚的带宽。
     *
     * @param path     草稿模型 .gguf 路径（要和主模型共用同一套词表，比如 Qwen3 全系）
     * @param threads  草稿模型线程数，建议 2~4：它在主模型算的同时不占太多核
     * @param maxDraft γ，每轮猜几个。6 是手机上的甜点，太大草稿自己就成了瓶颈
     * @return true = 加速已生效；false = 没挂上（主模型照常能用，原因见 getLastError）
     */
    fun loadDraft(path: String?, threads: Int = 4, maxDraft: Int = 6): Boolean {
        if (handle == 0L) return false
        val p = if (path.isNullOrBlank()) "none" else path
        return try {
            initDraft(handle, p, threads, maxDraft)
        } catch (t: Throwable) {
            Log.e(TAG, "挂草稿模型失败: ${t.message}")
            false
        }
    }

    /**
     * v10.94 n-gram 自投机：不用第二个模型，直接从自己已输出的 token 历史里
     * 找「上次出现同样前缀之后接了什么」，作为候选走投验。
     *
     * 改写、续写、重复列举这类内容命中率很高；闲聊几乎没命中，会自动止损。
     * 零额外内存（只存几千个 int），开着的代价可以忽略。
     */
    fun enableNgram(enable: Boolean, n: Int = 4, gamma: Int = 8): Boolean {
        if (handle == 0L) return false
        return try {
            initNgram(handle, enable, n, gamma)
        } catch (t: Throwable) {
            Log.e(TAG, "配置 n-gram 失败: ${t.message}")
            false
        }
    }
}
