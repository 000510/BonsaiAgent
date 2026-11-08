package com.example.bonsaiagent.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LiteRT-LM 推理后端。
 *
 * 为什么要有这个东西
 * ------------
 * llama.cpp 在联发科阵营没有 NPU 后端（只有高通的 ggml-hexagon）。
 * 而 Google 与联发科合作的 LiteRT NeuroPilot Accelerator 官方支持 MT6989（天玑 9300），
 * 这是我们唯一能碰到 NPU 的路。
 *
 * 和 llama.cpp 的区别
 * -----------------
 * · 模型格式不同：这里吃 .litertlm，不是 .gguf
 * · 不需要 JNI/CMake：官方 AAR 里带了 liblitertlm_jni.so
 * · 对话历史由 Conversation 自己管，不像 llama.cpp 要手动拼 prompt
 *
 * API 全部是对着 AAR 的 classes.jar 反编译确认过的（javap），不是猜的。
 */
object LiteRtEngine {

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null

    @Volatile var lastError: String = ""
        private set

    fun isLoaded(): Boolean = engine?.isInitialized() == true

    fun currentBackend(): String = backendTag

    private var backendTag: String = "未加载"

    /**
     * 加载模型。
     *
     * @param backendName "NPU" / "GPU" / "CPU"
     * @param threads CPU 后端用的线程数（NPU/GPU 忽略）
     */
    fun load(ctx: Context, modelPath: String, backendName: String, threads: Int): Boolean {
        unload()
        lastError = ""
        return try {
            val backend = when (backendName.uppercase()) {
                // NPU 需要传 nativeLibraryDir —— 厂商的 NPU 驱动 so 在那里面找
                "NPU" -> Backend.NPU(ctx.applicationInfo.nativeLibraryDir)
                "GPU" -> Backend.GPU()
                else -> Backend.CPU(threads)
            }
            backendTag = backendName.uppercase()

            val cfg = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 2048
            )
            val e = Engine(cfg)
            e.initialize()
            engine = e
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            unload()
            false
        }
    }

    /** 开一轮新对话（重置历史） */
    fun newConversation(systemPrompt: String, temp: Double, topK: Int, topP: Double) {
        val e = engine ?: return
        try {
            conversation?.close()
            conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    // SamplerConfig(topK, topP, temperature, seed)
                    samplerConfig = SamplerConfig(topK, topP, temp, -1)
                )
            )
        } catch (t: Throwable) {
            lastError = "创建对话失败: ${t.javaClass.simpleName} ${t.message}"
            conversation = null
        }
    }

    /**
     * 生成。阻塞直到结束，每个增量片段通过 onPiece 回调。
     *
     * 注意：官方回调给的是 Message 对象，不是字符串。
     * `contents.toString()` 能不能拿到纯文本还不确定 ——
     * 这里做了"累积 or 增量"的自适应判断，两种都能用。
     */
    fun generate(userText: String, maxWaitSec: Long = 300, onPiece: (String) -> Unit): String {
        val conv = conversation ?: run {
            lastError = "对话未初始化"
            return ""
        }

        val acc = StringBuilder()      // 最终文本
        val latch = CountDownLatch(1)
        var err: Throwable? = null

        try {
            conv.sendMessageAsync(userText, object : MessageCallback {
                override fun onMessage(message: Message) {
                    val raw = message.contents?.toString().orEmpty()
                    if (raw.isEmpty()) return
                    // 自适应：如果新内容是"以当前累积内容开头"，说明回调给的是累积全文 → 替换
                    val cur = acc.toString()
                    if (cur.isNotEmpty() && raw.length > cur.length && raw.startsWith(cur)) {
                        // 累积模式：算出增量再回调
                        onPiece(raw.substring(cur.length))
                        acc.setLength(0)
                        acc.append(raw)
                    } else if (cur.isEmpty() || !raw.startsWith(cur)) {
                        // 增量模式
                        acc.append(raw)
                        onPiece(raw)
                    }
                }

                override fun onDone() {
                    latch.countDown()
                }

                override fun onError(t: Throwable) {
                    err = t
                    latch.countDown()
                }
            })
        } catch (t: Throwable) {
            lastError = "发送失败: ${t.javaClass.simpleName} ${t.message}"
            return ""
        }

        val ok = try {
            latch.await(maxWaitSec, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!ok) lastError = "生成超时（${maxWaitSec} 秒）"
        err?.let { lastError = "生成出错: ${it.javaClass.simpleName} ${it.message}" }

        return acc.toString()
    }

    fun unload() {
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
        backendTag = "未加载"
    }
}
