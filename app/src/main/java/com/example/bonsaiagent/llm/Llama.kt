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
}
