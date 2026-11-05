package com.example.bonsaiagent

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bonsaiagent.agent.CloudClient
import com.example.bonsaiagent.agent.Executor
import com.example.bonsaiagent.agent.Router
import com.example.bonsaiagent.agent.RouteResult
import com.example.bonsaiagent.api.LocalApiServer
import com.example.bonsaiagent.data.AgentSettings
import com.example.bonsaiagent.data.SettingsStore
import com.example.bonsaiagent.llm.GgufTypeFixer
import com.example.bonsaiagent.llm.Llama
import com.example.bonsaiagent.llm.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条聊天消息（content 用 State，便于流式更新触发重组） */
class ChatMessage(val role: String) {
    val content = mutableStateOf("")
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private var apiServer: LocalApiServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val settings = mutableStateOf(store.load())
    val modelPath = mutableStateOf(ModelLoader.currentModelPath(app))
    val modelLoaded = mutableStateOf(Llama.isLoaded())
    val status = mutableStateOf("欢迎使用 BonsaiAgent")
    val chatMessages = mutableStateListOf<ChatMessage>()
    val agentMessages = mutableStateListOf<ChatMessage>()

    init {
        // 原生层崩过 / 上次运行到哪一步，这里能直接看到
        val crashFile = java.io.File(app.filesDir, "last_crash.txt")
        val logFile = java.io.File(app.filesDir, "run_log.txt")
        val errFile = java.io.File(app.filesDir, "native_stderr.txt")
        val crash = if (crashFile.exists()) {
            val t = try { crashFile.readText().trim() } catch (e: Throwable) { "" }
            crashFile.delete()
            t
        } else ""
        val log = if (logFile.exists()) {
            try { logFile.readLines().takeLast(25).joinToString("\n") } catch (e: Throwable) { "" }
        } else ""
        val nerr = if (errFile.exists()) {
            try { errFile.readLines().takeLast(30).joinToString("\n").trim() } catch (e: Throwable) { "" }
        } else ""
        val tail = buildString {
            if (log.isNotEmpty()) append("\n\n日志：\n").append(log)
            if (nerr.isNotEmpty()) append("\n\n底层输出：\n").append(nerr)
        }
        when {
            crash.isNotEmpty() -> status.value = "上次原生崩溃：\n$crash$tail"
            tail.isNotEmpty()  -> status.value = "上次运行记录：$tail"
        }

        // v3.2：抓系统 logcat。
        // 目的：v3.1 的所有 wrap(abort/assert/...) 都没触发、stderr 也全空，
        // 说明进程不是"自己退出的"。只剩两种可能，且必须先分清：
        //   ① 硬崩溃(SIGSEGV/SIGBUS) —— logcat 会有 "Fatal signal"，crash buffer 有 backtrace
        //   ② 被系统 SIGKILL(MIUI 杀后台 / LMK) —— logcat 会有 lowmemorykiller / Killing
        // 这两者如果没 logcat，看起来完全一样，所以这一步是"定责"的关键。
        Thread {
            val lc = dumpLogcat()
            if (lc.isNotBlank()) {
                try {
                    java.io.File(app.filesDir, "logcat_dump.txt").writeText(lc)
                } catch (e: Throwable) { /* 忽略 */ }
                mainHandler.post {
                    status.value = status.value + "\n\n===== 系统日志 =====\n" + lc
                }
            }
        }, "logcat-dump").start()
    }

    /**
     * 读自己进程的 logcat（Android 允许 app 读自己 uid 的日志）。
     * - crash buffer：原生崩溃记录，含 tombstone 的 backtrace，信息量最大
     * - main buffer：普通的进程活动，能看出是否被 LMK / 系统杀掉
     * 只保留可能有用的行，避免把状态栏撑爆。
     */
    private fun dumpLogcat(): String {
        val keys = listOf(
            "Fatal signal", "SIGSEGV", "SIGBUS", "SIGILL", "SIGABRT", "SIGKILL",
            "tombstone", "DEBUG", "libbonsai", "bonbai", "bonsai", "BonsaiLLM",
            "lowmemorykiller", "Killing", "kill", "ANR", "OutOfMemory", "!!!",
            "fatal", "Abort", "abort", "dlopen", "UnsatisfiedLink"
        )
        fun looksImportant(l: String) = keys.any { l.contains(it, ignoreCase = true) }

        val sb = StringBuilder()
        val bufs = listOf(
            listOf("logcat", "-b", "crash", "-d", "-v", "time"),
            listOf("logcat", "-b", "main", "-d", "-v", "time", "-t", "4000")
        )
        for (cmd in bufs) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                val kept = out.lines().filter { it.isNotBlank() && looksImportant(it) }.takeLast(45)
                if (kept.isNotEmpty()) {
                    sb.append("\n[").append(cmd[2]).append("]\n")
                    sb.append(kept.joinToString("\n"))
                }
            } catch (e: Throwable) {
                sb.append("\n[").append(cmd[2]).append("] 读取失败: ").append(e.message)
            }
        }
        return sb.toString().trim()
    }

    /** 从系统选择器导入 .gguf 并加载 */
    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = ModelLoader.importFromUri(getApplication(), uri)
            withContext(Dispatchers.Main) {
                if (p != null) {
                    modelPath.value = p
                    status.value = "模型已导入，正在加载…"
                    loadModel()
                } else {
                    status.value = "导入失败"
                }
            }
        }
    }

    fun loadModel() {
        val path = modelPath.value ?: run {
            status.value = "没有可用的模型文件"; return
        }
        viewModelScope.launch(Dispatchers.IO) {
            status.value = "正在加载模型…"
            // 先修一下 GGUF 里的旧版类型标签（42/41 → 142/141），省得重下 2GB
            val fixed = try {
                GgufTypeFixer.fixIfNeeded(path)
            } catch (e: Throwable) {
                null
            }
            if (fixed != null) status.value = fixed + "，正在加载…"
            // v1.10 排查版：强制单线程，排除 ggml 多线程 kernel 的竞态问题
            val ok = Llama.load(path, 1, settings.value.contextSize)
            withContext(Dispatchers.Main) {
                modelLoaded.value = ok
                status.value = if (ok) {
                    if (fixed != null) "模型就绪 ✅（$fixed）" else "模型就绪 ✅"
                } else {
                    val err = Llama.getLastError()
                    "模型加载失败" + if (err.isNotBlank()) "：\n${err.trim()}" else "，请确认是 prism 分支支持的格式"
                }
            }
        }
    }

    /** 页面1：纯本地对话 */
    fun sendChat(text: String) {
        if (!Llama.isLoaded()) { status.value = "请先在设置页导入并加载模型"; return }
        val msg = text.trim()
        if (msg.isBlank()) return
        val user = ChatMessage("user").apply { content.value = msg }
        val ai = ChatMessage("assistant")
        chatMessages.add(user); chatMessages.add(ai)
        val s = settings.value
        // ChatML 格式：Qwen2.5 / Qwen3(即 Ternary-Bonsai) 必须这么套，否则输出会乱
        val prompt = buildString {
            append("<|im_start|>system\n")
            append("你是一个运行在手机上的本地助手，请用简洁、准确的中文回答。<|im_end|>\n")
            append("<|im_start|>user\n").append(msg).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val buf = StringBuilder()
            // 推理要用大栈线程：llama.cpp 的计算在调用线程栈上跑，默认 1MB 容易溢出
            val worker = Thread(null, Runnable {
                Llama.generate(prompt, s.temperature, s.topK, s.topP, s.repeatPenalty, s.maxTokens) {
                    buf.append(it)
                    val txt = buf.toString()
                    mainHandler.post { ai.content.value = txt }
                }
            }, "llama-gen")   // v2.2: 回退自定义栈大小(32MB)，用默认栈
            worker.start()
            worker.join()
        }
    }

    /** 页面2：混合路由 + Agent 执行 */
    fun sendAgent(text: String) {
        if (!Llama.isLoaded()) { status.value = "请先在设置页导入并加载模型"; return }
        val msg = text.trim()
        if (msg.isBlank()) return
        val user = ChatMessage("user").apply { content.value = msg }
        val ai = ChatMessage("assistant")
        agentMessages.add(user); agentMessages.add(ai)
        val s = settings.value
        // ChatML 格式（Qwen 系用）
        val prompt = buildString {
            append("<|im_start|>system\n").append(s.systemPrompt).append("<|im_end|>\n")
            append("<|im_start|>user\n").append(msg).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val raw = StringBuilder()
            val worker = Thread(null, Runnable {
                Llama.generate(prompt, s.temperature, s.topK, s.topP, s.repeatPenalty, s.maxTokens) {
                    raw.append(it)
                    val txt = raw.toString()
                    mainHandler.post { ai.content.value = txt }
                }
            }, "llama-gen")   // v2.2: 回退自定义栈大小(32MB)，用默认栈
            worker.start()
            worker.join()
            // 解析路由指令
            when (val route = Router.parse(ai.content.value)) {
                is RouteResult.Cloud -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 已路由云端] ${route.description}\n" }
                    val cloud = CloudClient.chat(s.apiBase, s.deepseekKey, route.description)
                    mainHandler.post { ai.content.value += cloud }
                }
                is RouteResult.Exec -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 执行命令] ${route.command}\n" }
                    val out = Executor.run(route.command)
                    mainHandler.post { ai.content.value += out }
                }
                else -> { /* 直接回答，无需路由 */ }
            }
        }
    }

    fun updateSettings(s: AgentSettings) {
        settings.value = s
        store.save(s)
        if (s.apiEnabled && apiServer == null) {
            apiServer = LocalApiServer {
                Triple(settings.value.temperature, settings.value.topK, settings.value.topP)
            }
            apiServer?.start()
            status.value = "本机 API 已开启 (127.0.0.1:11434)"
        } else if (!s.apiEnabled && apiServer != null) {
            apiServer?.stop(); apiServer = null
            status.value = "本机 API 已关闭"
        }
    }

    override fun onCleared() {
        apiServer?.stop()
        Llama.unload()
    }
}
