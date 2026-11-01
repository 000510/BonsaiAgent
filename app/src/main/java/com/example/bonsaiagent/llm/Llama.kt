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
    private external fun setCallback(cb: Callback)
    private external fun generate(handle: Long, prompt: String, temp: Float, topK: Int,
                                  topP: Float, repPen: Float, maxTokens: Int): String
    private external fun freeNative(handle: Long)

    fun isLoaded(): Boolean = handle != 0L

    /** 加载 .gguf 模型；threads 建议 = CPU 大核数，nCtx = 上下文窗口 */
    fun load(modelPath: String, threads: Int, nCtx: Int): Boolean {
        if (handle != 0L) unload()
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
    fun generate(prompt: String, temp: Float, topK: Int, topP: Float, repPen: Float,
                 maxTokens: Int, onPiece: (String) -> Unit): String {
        pieceSink = onPiece
        return generate(handle, prompt, temp, topK, topP, repPen, maxTokens)
    }

    fun unload() {
        if (handle != 0L) {
            freeNative(handle)
            handle = 0L
        }
    }
}
