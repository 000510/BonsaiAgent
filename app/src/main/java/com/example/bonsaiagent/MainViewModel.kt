package com.example.bonsaiagent

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bonsaiagent.agent.Actions
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 推理状态（给界面上的可移动状态条用）。
 * phase: 就绪 / 加载中 / 处理输入 / 生成中 / 完成
 */
data class GenStatus(
    val phase: String = "就绪",
    val tokPerSec: Float = 0f,
    val tokens: Int = 0,
    val chars: Int = 0
)

// 全局自增，保证每条消息的 id 唯一 —— LazyColumn 的 key 必须是能存进 Bundle 的简单类型
private var chatMsgSeq = 0L

/** 一条聊天消息（content 用 State，便于流式更新触发重组） */
class ChatMessage(val role: String) {
    // v3.3 关键修复：给每条消息一个稳定的 Long id。
    // 之前 LazyColumn 写成 items(list, key = { it })，直接把 ChatMessage 对象当 key，
    // Compose 存状态时抛 "Type of the key ... is not supported" —— 主线程异常 → 进程直接死。
    // 表现和 native 崩溃一模一样：瞬间闪退、没有 tombstone、日志停在半路。
    val id: Long = ++chatMsgSeq
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
    // v6.0：界面状态条用的实时状态（替代原来刷屏的实时日志）
    val genStatus = mutableStateOf(GenStatus())
    /** 上次崩溃现场（保留不删，供诊断分享） */
    val lastCrash = mutableStateOf("")
    val chatMessages = mutableStateListOf<ChatMessage>()
    val agentMessages = mutableStateListOf<ChatMessage>()

    init {
        // 原生层崩过 / 上次运行到哪一步，这里能直接看到
        val crashFile = java.io.File(app.filesDir, "last_crash.txt")
        val logFile = java.io.File(app.filesDir, "run_log.txt")
        val errFile = java.io.File(app.filesDir, "native_stderr.txt")
        // v6.3：崩溃记录读完不再删除，留一份给"分享诊断"，否则点按钮时已经没了
        val crash = if (crashFile.exists()) {
            try { crashFile.readText().trim() } catch (e: Throwable) { "" }
        } else ""
        lastCrash.value = crash
        // v6.3：界面上只留摘要。完整内容点"分享诊断"一键带走，
        // 不用再在手机上长按拖选几百行日志。
        val log = if (logFile.exists()) {
            try { logFile.readLines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n") } catch (e: Throwable) { "" }
        } else ""
        val tail = buildString {
            if (log.isNotEmpty()) append("\n\n最近日志：\n").append(log)
        }
        when {
            crash.isNotEmpty() -> status.value = "上次异常退出：\n" + crash.take(300) + tail
            tail.isNotEmpty()  -> status.value = "上次运行：$tail"
        }

        // v3.2：抓系统 logcat。
        // 目的：v3.1 的所有 wrap(abort/assert/...) 都没触发、stderr 也全空，
        // 说明进程不是"自己退出的"。只剩两种可能，且必须先分清：
        //   ① 硬崩溃(SIGSEGV/SIGBUS) —— logcat 会有 "Fatal signal"，crash buffer 有 backtrace
        //   ② 被系统 SIGKILL(MIUI 杀后台 / LMK) —— logcat 会有 lowmemorykiller / Killing
        // 这两者如果没 logcat，看起来完全一样，所以这一步是"定责"的关键。
        Thread({
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
     * Ternary-Bonsai 这类推理模型的输出形如：
     *   <think>思考过程……</think>正式回答
     * 界面上只展示正式回答；还在思考时给一句提示。
     * 普通模型（Qwen 等）不含这些标记，会原样返回。
     *
     * @return (要显示的文本, 是否还在思考)
     */
    private fun extractAnswer(raw: String): Pair<String, Boolean> {
        val close = raw.indexOf("</think>")
        if (close >= 0) {
            return raw.substring(close + 8).trimStart() to false
        }
        if (raw.contains("<think>")) return "" to true
        return raw to false
    }

    /** 读某个诊断文件的末尾 n 行 */
    private fun readFileTail(name: String, n: Int): String = try {
        val f = java.io.File(getApplication<android.app.Application>().filesDir, name)
        if (!f.exists()) "" else f.readLines().takeLast(n).joinToString("\n")
    } catch (e: Throwable) { "" }

    /**
     * 把诊断信息拼成一段文本，供"复制/分享"按钮一键带走。
     * 目的：日志动辄几百行，在手机上长按选择再复制非常难受，
     * 这里只截最有用的部分（崩溃现场 + 日志末尾 + 底层输出末尾）。
     */
    fun buildDiagnostics(): String {
        val sb = StringBuilder()
        val app = getApplication<android.app.Application>()
        sb.appendLine("=== Bonsai Lite 诊断 ===")
        val dfNow = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        sb.appendLine("时间: " + dfNow.format(java.util.Date()))
        sb.appendLine("机型: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        sb.appendLine("系统: Android " + android.os.Build.VERSION.RELEASE + " / SDK " + android.os.Build.VERSION.SDK_INT)
        sb.appendLine("CPU 核数: " + Runtime.getRuntime().availableProcessors())
        sb.appendLine("App 版本: 6.3")
        sb.appendLine("模型: " + (modelPath.value ?: "未导入"))
        sb.appendLine()

        val crash = readFileTail("last_crash.txt", 12)
        if (crash.isNotBlank()) {
            sb.appendLine("--- 崩溃记录 ---")
            sb.appendLine(crash)
        }
        val log = readFileTail("run_log.txt", 40)
        if (log.isNotBlank()) {
            sb.appendLine("--- 日志末尾 ---")
            sb.appendLine(log)
        }
        val err = readFileTail("native_stderr.txt", 15)
        if (err.isNotBlank()) {
            sb.appendLine("--- 底层输出末尾 ---")
            sb.appendLine(err)
        }
        // 读全文而不是尾部：崩溃栈 #00 在开头，截尾会把最关键的帧丢掉
        val lc = try {
            val f = java.io.File(getApplication<android.app.Application>().filesDir, "logcat_dump.txt")
            if (f.exists()) f.readText().trim() else ""
        } catch (e: Throwable) { "" }
        if (lc.isNotBlank()) {
            sb.appendLine("--- 系统日志 ---")
            sb.appendLine(lc)
        }
        return sb.toString()
    }

    /** 读 run_log.txt 尾部若干行 */
    private fun readLogTail(n: Int = 30): String = try {
        val f = java.io.File(getApplication<android.app.Application>().filesDir, "run_log.txt")
        if (!f.exists()) "" else f.readLines().takeLast(n).joinToString("\n")
    } catch (e: Throwable) { "" }

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
        // crash buffer 要【从头】取：崩溃栈 #00 在最前面，
        // 之前用 takeLast 把最关键的 native 帧全丢了，只剩一堆 Java 帧。
        val bufs = listOf(
            Triple("logcat", "crash", 90),
            Triple("logcat", "main", 30)
        )
        for ((cmd, bufName, keep) in bufs) {
            val args = if (bufName == "crash")
                listOf(cmd, "-b", "crash", "-d", "-v", "time")
            else
                listOf(cmd, "-b", "main", "-d", "-v", "time", "-t", "4000")
            try {
                val pr = ProcessBuilder(args).redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().readText()
                pr.waitFor()
                val matched = out.lines().filter { it.isNotBlank() && looksImportant(it) }
                val kept = if (bufName == "crash") matched.take(keep) else matched.takeLast(keep)
                if (kept.isNotEmpty()) {
                    sb.append("\n[").append(bufName).append("]\n")
                    sb.append(kept.joinToString("\n"))
                }
            } catch (e: Throwable) {
                sb.append("\n[").append(bufName).append("] 读取失败: ").append(e.message)
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
        genStatus.value = GenStatus(phase = "加载中")
        viewModelScope.launch(Dispatchers.IO) {
            status.value = "正在加载模型…"
            // 先修一下 GGUF 里的旧版类型标签（42/41 → 142/141），省得重下 2GB
            val fixed = try {
                GgufTypeFixer.fixIfNeeded(path)
            } catch (e: Throwable) {
                null
            }
            if (fixed != null) status.value = fixed + "，正在加载…"
            // v4.0：不再强制单线程。
            // 之前为了排除多线程竞态写死 1 线程，代价是手机 8 个核只用 1 个，
            // 光这一项就是数倍差距（真凶已查明是 UI 的 item key，与线程无关）。
            // 取 CPU 核数，但上限 4。手机多是"4 大核 + 4 小核"，
            // 线程开到 6~8 会把任务调度到小核上，反而更慢，还更容易降频。
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
            // 动态后端需要知道 .so 目录（ggml 会在这里找 libggml-cpu-android_armvX_*.so）
            val nativeDir = getApplication<android.app.Application>().applicationInfo.nativeLibraryDir
            val ok = Llama.load(path, cores, settings.value.contextSize, nativeDir)
            withContext(Dispatchers.Main) {
                modelLoaded.value = ok
                genStatus.value = GenStatus(phase = "就绪")
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
        //
        // v4.3：改为带上历史对话。
        // 之前只把当前这一句丢给模型，模型天生"失忆"——你说"饿了"，它不知道你刚才
        // 说过什么，只能给一句通用回复，看起来就像智力下降。
        // 上下文只有 2048 token，所以只取最近若干轮，并按字符数粗估截断。
        val history = mutableListOf<String>()
        var estChars = 0
        for (m in chatMessages.dropLast(1).asReversed()) {   // dropLast(1) 去掉刚加的空 assistant
            val body = m.content.value
            if (body.isBlank()) continue
            val role = if (m.role == "user") "user" else "assistant"
            val piece = "<|im_start|>" + role + "\n" + body + "<|im_end|>\n"
            if (estChars + piece.length > 500) break           // 约 500 token，把上下文空间让给长文生成
            history.add(0, piece)
            estChars += piece.length
        }
        // v6.7：如果用户指定了字数（"写600字""1000字作文"），临时加强约束。
        // 8B 模型没有字数计数器，全靠感觉，实测要求 1000 字只写 700 字就收尾。
        // 这里把要求提到 system 里，并给出"分段"这种可执行的结构，
        // 比单纯重复"1000字"有效得多。只对本次请求生效，不影响普通聊天。
        val wantLen = Regex("""(\d{2,5})\s*字""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        val nowForChat = java.text.SimpleDateFormat(
            "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA
        ).format(java.util.Date())
        val sysText = if (wantLen != null && wantLen >= 100) {
            val paras = (wantLen / 150).coerceIn(3, 12)
            "你是中文写作助手。用户要求约${wantLen}字，请分${paras}个自然段写完，" +
            "每段约150字，务必写满${wantLen}字以上，不要提前收尾，不要重复。"
        } else {
            "你是手机本地助手，回答简洁准确。"
        }
        val sysTextWithTime = sysText + "\n当前时间：" + nowForChat
        val prompt = buildString {
            append("<|im_start|>system\n")
            append(sysTextWithTime).append("<|im_end|>\n")
            for (h in history) append(h)
            append("<|im_start|>assistant\n")
            // v6.6：跳过思考阶段。
            // Ternary-Bonsai 是推理模型，会在 <think>...</think> 里先想一大段。
            // 实测让它自由思考：写 1000 字作文时闷头想了 200 个 token（约 30 秒）
            // 然后崩溃，界面上一直显示"思考中…"，用户什么也看不到。
            // 这里直接把思维链的结尾预填进去，模型会认为思考已结束，立刻开始写正文。
            // 代价：复杂推理的质量会略降；收益：少等半分钟，且避开这条崩溃路径。
            append("<think>\n\n</think>\n")
        }
        genStatus.value = GenStatus(phase = "处理输入")
        viewModelScope.launch(Dispatchers.IO) {
            val buf = StringBuilder()
            val t0 = System.currentTimeMillis()
            var firstTokAt = 0L
            var tokCount = 0
            // 推理要用大栈线程：llama.cpp 的计算在调用线程栈上跑，默认 1MB 容易溢出
            val worker = Thread(null, Runnable {
                Llama.generate(prompt, s.temperature, s.topK, s.topP, s.repeatPenalty, s.maxTokens) {
                    buf.append(it)
                    tokCount++
                    // 速度只从"第一个字出来之后"开始算，否则会把 prefill 时间摊进去，显得很慢
                    if (firstTokAt == 0L) firstTokAt = System.currentTimeMillis()
                    val (answer, thinking) = extractAnswer(buf.toString())
                    val show = if (thinking) "（思考中…）" else answer
                    val sec = (System.currentTimeMillis() - firstTokAt) / 1000.0
                    val speed = if (sec > 0.05) (tokCount / sec).toFloat() else 0f
                    val snap = GenStatus(if (thinking) "思考中" else "生成中", speed, tokCount, show.length)
                    mainHandler.post {
                        ai.content.value = show
                        genStatus.value = snap
                    }
                }
            }, "llama-gen")   // v2.2: 回退自定义栈大小(32MB)，用默认栈
            worker.start()
            worker.join()
            val st = genStatus.value
            mainHandler.post { genStatus.value = st.copy(phase = "完成") }
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
        // v7.0：跟对话页对齐——带上最近几轮历史，并跳过 <think> 思考。
        // 路由判断是"看一眼决定走哪条路"，让它思考 200 个 token 纯属浪费
        //（实测要等 30 秒，还进过崩溃路径）。
        val aHistory = mutableListOf<String>()
        var aChars = 0
        for (m in agentMessages.dropLast(1).asReversed()) {
            val body = m.content.value
            if (body.isBlank()) continue
            val role = if (m.role == "user") "user" else "assistant"
            val piece = "<|im_start|>" + role + "\n" + body + "<|im_end|>\n"
            if (aChars + piece.length > 200) break   // v7.4：Agent 每轮基本独立，历史压到 200 字符，prefill 少一半
            aHistory.add(0, piece)
            aChars += piece.length
        }
        // v7.2：把真实时间写进 system，模型自己不知道现在几点（实测会编出 2023 年的日期）
        val nowStr = java.text.SimpleDateFormat(
            "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA
        ).format(java.util.Date())
        val sysAgent = s.systemPrompt + "\n当前时间：" + nowStr

        val prompt = buildString {
            append("<|im_start|>system\n").append(sysAgent).append("<|im_end|>\n")
            for (h in aHistory) append(h)
            append("<|im_start|>assistant\n")
            append("<think>\n\n</think>\n")
        }
        genStatus.value = GenStatus(phase = "处理输入")
        viewModelScope.launch(Dispatchers.IO) {
            val raw = StringBuilder()
            var firstTokAt = 0L
            var tokCount = 0
            val worker = Thread(null, Runnable {
                Llama.generate(prompt, s.temperature, s.topK, s.topP, s.repeatPenalty, s.maxTokens) {
                    raw.append(it)
                    tokCount++
                    if (firstTokAt == 0L) firstTokAt = System.currentTimeMillis()
                    val (answer, thinking) = extractAnswer(raw.toString())
                    val show = if (thinking) "（思考中…）" else answer
                    val sec = (System.currentTimeMillis() - firstTokAt) / 1000.0
                    val speed = if (sec > 0.05) (tokCount / sec).toFloat() else 0f
                    val snap = GenStatus(if (thinking) "思考中" else "生成中", speed, tokCount, show.length)
                    mainHandler.post {
                        ai.content.value = show
                        genStatus.value = snap
                    }
                }
            }, "llama-gen")   // v2.2: 回退自定义栈大小(32MB)，用默认栈
            worker.start()
            worker.join()
            mainHandler.post { genStatus.value = genStatus.value.copy(phase = "完成") }
            // 解析路由指令
            when (val route = Router.parse(ai.content.value)) {
                is RouteResult.Cloud -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 已路由云端] ${route.description}\n" }
                    val cloud = CloudClient.chat(s.apiBase, s.deepseekKey, route.description)
                    mainHandler.post { ai.content.value += cloud }
                }
                is RouteResult.Action -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 动作] ${route.name}${route.arg?.let { " ($it)" } ?: ""}\n" }
                    val app = getApplication<android.app.Application>()
                    val out = Actions.run(app, route.name, route.arg)
                    mainHandler.post { ai.content.value += out }
                }
                is RouteResult.Exec -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 执行命令] ${route.command}\n" }
                    val out = Executor.run(route.command, s.allowDangerous)
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
