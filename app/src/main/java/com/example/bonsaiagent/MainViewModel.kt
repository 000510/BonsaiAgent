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
import com.example.bonsaiagent.agent.AppIndex
import com.example.bonsaiagent.agent.CloudClient
import com.example.bonsaiagent.agent.Executor
import com.example.bonsaiagent.agent.FastRoute
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
    val chars: Int = 0,
    /** 本轮已耗时（秒）。prefill 阶段就是 TTFT，用来验证前缀缓存到底省了多少 */
    val elapsedSec: Double = 0.0
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
    /** 已索引的桌面应用数量（设置页显示，0 表示还没扫到——多半是 <queries> 没生效） */
    val appCount = mutableStateOf(0)

    init {
        // v10.0：后台扫一遍手机里装了哪些应用，给「打开微信」这类指令用。
        // 有缓存就直接读缓存（毫秒级），没缓存才真的去问系统。
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val n = AppIndex.all(getApplication()).size
                val real = if (n == 0) AppIndex.refresh(getApplication()) else n
                withContext(Dispatchers.Main) { appCount.value = real }
            } catch (_: Throwable) { /* 扫描失败不影响其它功能 */ }
        }

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
            val ans = raw.substring(close + 8).trimStart()
            if (ans.isNotEmpty()) return ans to false
            // v9.2：</think> 之后是空的 —— 小模型（1.5B）不理解这个约定，
            // 会把正文全写进思考区。这时退回去用思考区的内容，总比什么都不显示好。
            val open = raw.indexOf("<think>")
            if (open >= 0 && close > open) {
                val inner = raw.substring(open + 7, close).trim()
                if (inner.isNotEmpty()) return inner to false
            }
            return "（模型没有输出内容，可以换个说法再问一次）" to false
        }
        if (raw.contains("<think>")) {
            // 思考区没闭合：把已有的内容先显示出来，别让用户干等一个省略号
            val open = raw.indexOf("<think>")
            val inner = raw.substring(open + 7).trimStart()
            if (inner.isNotEmpty()) return inner to true
            return "" to true
        }
        if (raw.isBlank()) return "（模型没有输出内容，可以换个说法再问一次）" to false
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
    /** 设置页「刷新应用列表」：重新读一遍系统应用清单 */
    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = try { AppIndex.refresh(getApplication()) } catch (_: Throwable) { 0 }
            withContext(Dispatchers.Main) {
                appCount.value = n
                status.value = if (n > 0) "已索引 $n 个应用" else
                    "没扫到应用（检查 manifest 的 <queries> 是否生效）"
            }
        }
    }

    fun buildDiagnostics(): String {
        val sb = StringBuilder()
        val app = getApplication<android.app.Application>()
        sb.appendLine("=== Bonsai Lite 诊断 ===")
        val dfNow = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        sb.appendLine("时间: " + dfNow.format(java.util.Date()))
        sb.appendLine("机型: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        sb.appendLine("系统: Android " + android.os.Build.VERSION.RELEASE + " / SDK " + android.os.Build.VERSION.SDK_INT)
        sb.appendLine("CPU 核数: " + Runtime.getRuntime().availableProcessors())
        val vn = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Throwable) { "?" }
        sb.appendLine("App 版本: $vn")
        sb.appendLine("设置线程数: ${settings.value.threads}（改了必须重新加载模型才生效）")
        sb.appendLine("模型: " + (modelPath.value ?: "未导入"))
        sb.appendLine()

        val crash = readFileTail("last_crash.txt", 12)
        if (crash.isNotBlank()) {
            sb.appendLine("--- 崩溃记录 ---")
            sb.appendLine(crash)
        }
        // 线程/上下文相关的行最关键，单独捞出来，避免被最近 40 行挤掉
        try {
            val all = java.io.File(app.filesDir, "run_log.txt")
            if (all.exists()) {
                val key = all.readLines().filter {
                    it.contains("上下文就绪") || it.contains("n_threads") || it.contains("线程") ||
                    it.contains("KV") || it.contains("续接") || it.contains("前缀") ||
                    it.contains("prefill") || it.contains("prompt ")
                }.takeLast(14)   // v10.15：KV 续接/前缀复用是核心指标，必须能看到
                if (key.isNotEmpty()) {
                    sb.appendLine("--- 关键行 ---")
                    key.forEach { sb.appendLine(it) }
                }
            }
        } catch (_: Throwable) { }
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
            // ★ v10.11 关键修复：这里原来写的是
            //     Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
            // 也就是说**根本不读用户设置**，8 核手机永远被压成 6 线程。
            // 设置页那个线程滑块整整几版都是摆设 ——
            // 用户测 1/4/8 完全一样，日志永远 6，全是因为这一行。
            val cores = settings.value.threads.coerceIn(1, 16)
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
        // ★ v10.13：历史长度从 500 字符砍到 200，并且最多 2 轮。
        //
        // 为什么：KV 缓存只能缓存 system 那段（固定 20 token），
        // 历史每轮都变，没法缓存 —— 全部要重新 prefill。
        // 实测第二轮带上一轮的 200 字作文当历史，prefill 直接从 6 秒涨到 30 秒。
        // 生成只有 6 tok/s，多带一轮历史的代价远比"记得更多"值钱。
        // v10.14：历史恢复完整，不再砍。
        //
        // 上一版为了压 prefill 把历史砍到 2 轮共 200 字符，但那样做多轮 Agent 操作会失忆。
        // 现在改动方式变了 —— native 层做「整段序列续接」：
        // 只要 prompt 是上一轮的**追加**（历史只增不改），
        // 前面算过的 KV 全部复用，prefill 只算新增的十几个 token。
        //
        // 唯一要求：历史必须保持"只追加"。所以这里不做任何截断/改写，
        // 只保留一个很大的硬上限防止撞爆上下文（约 2400 字符）。
        val history = mutableListOf<String>()
        var estChars = 0
        for (m in chatMessages.dropLast(1).asReversed()) {   // dropLast(1) 去掉刚加的空 assistant
            val body = m.content.value
            if (body.isBlank()) continue
            val role = if (m.role == "user") "user" else "assistant"
            val piece = "<|im_start|>" + role + "\n" + body + "<|im_end|>\n"
            if (estChars + piece.length > 2400) break
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
        // ★ v10.12：对话页也接上 KV 前缀缓存。
        //
        // 之前这里调的是 Llama.generate("", prompt, ...) —— 前缀传空，
        // 于是每一轮都把 system + 历史 + 问题整段重新 prefill 一遍，
        // 实测 13 秒才出第一个字（路由页有缓存只要 4 秒）。
        //
        // 要缓存生效，system 部分必须**逐字节不变**，所以：
        //   · 时间戳、字数要求这些每轮都变的东西，全部挪到 user 消息里
        //   · system 只留一句固定的角色描述
        val sysFixed = "你是手机本地助手，回答简洁准确。"
        val sysPrefix = "<|im_start|>system\n" + sysFixed + "<|im_end|>\n"

        // 字数要求 + 时间，作为前缀加到最新那条用户消息上
        // 续写类请求要单独提示：实测模型会把上一轮的整篇文章重写一遍，
        // 而不是接着往下写（用户说"再续写100字"，它从头又写了一遍）
        val isContinue = Regex("续写|接着写|继续写|往下写|补充").containsMatchIn(msg)
        val latestUserHint = buildString {
            append("[现在 ").append(nowForChat).append("] ")
            if (wantLen != null && wantLen >= 100) {
                val paras = (wantLen / 150).coerceIn(3, 12)
                append("（请写约").append(wantLen).append("字，分").append(paras)
                append("个自然段写完，每段约150字，务必写满，不要提前收尾，不要重复）")
            }
            if (isContinue) {
                append("（这是续写请求：只输出新增的那一段，绝对不要重复前面已经写过的内容）")
            }
        }

        // 找出**最后一条**（也就是最新）用户消息，提示要插在它那里
        var lastUserIdx = -1
        for (i in history.indices) {
            if (history[i].startsWith("<|im_start|>user")) lastUserIdx = i
        }
        val prompt = buildString {
            for ((i, h) in history.withIndex()) {
                if (i == lastUserIdx) {
                    append("<|im_start|>user\n").append(latestUserHint)
                    append(h.removePrefix("<|im_start|>user\n"))
                } else {
                    append(h)
                }
            }
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
                Llama.generate(sysPrefix, prompt, s.temperature, s.topK, s.topP, s.repeatPenalty, s.maxTokens) {
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
                        // ★ v10.11：对话页必须实时显示内容。
                        // v10.4 那次改动本意只针对路由页（那里模型会复读提示词），
                        // 但批量替换把对话页这一处也改了 ——
                        // 结果对话页生成时界面一片空白，表现就是"吞回答"。
                        ai.content.value = show
                        genStatus.value = snap
                    }
                }
            }, "llama-gen")   // v2.2: 回退自定义栈大小(32MB)，用默认栈
            worker.start()
            while (worker.isAlive) {
                val el = (System.currentTimeMillis() - t0) / 1000.0
                val cur = genStatus.value
                if (cur.phase == "处理输入") {
                    genStatus.value = cur.copy(elapsedSec = el)
                }
                delay(120)
            }
            worker.join()
            // 兜底：万一流式回调一个字都没写进去（比如首字就被裁掉），
            // 这里用完整缓冲再落一次，保证界面上不会只剩一个省略号
            if (ai.content.value.isBlank()) {
                mainHandler.post { ai.content.value = extractAnswer(buf.toString()).first }
            }
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
        // v8.1：Agent 不再拼接历史（每轮独立判断，省下的 prefill 时间非常可观）
        // v7.2：把真实时间写进 system，模型自己不知道现在几点（实测会编出 2023 年的日期）
        val nowStr = java.text.SimpleDateFormat(
            "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA
        ).format(java.util.Date())
        // 注意：时间绝对不能放进 sysPrefix —— 它每过一分钟就变，
        // 会导致 KV 前缀缓存每轮失效（实测表现为 18→20→24 秒越来越慢）。
        // 时间改放到用户消息里，前缀就彻底固定了。
        val sysAgent = s.systemPrompt

        // v7.5 快路径：高频短指令直接命中，不经过模型。
        // 实测这类指令占 prefill 33 秒的绝大部分，而意图是确定的，查表就够。
        FastRoute.match(getApplication(), msg)?.let { hit ->
            when (hit) {
                is FastRoute.Hit.Answer -> ai.content.value = hit.text
                is FastRoute.Hit.Act -> {
                    ai.content.value = "[→ 动作] ${hit.action}（快路径，0秒）\n"
                    ai.content.value += Actions.run(getApplication(), hit.action, hit.arg)
                }
            }
            genStatus.value = GenStatus(phase = "就绪")
            return
        }

        // v8.0：把 prompt 拆成"固定前缀"和"变化部分"。
        // 前缀（system）每轮完全一样，交给 native 层做 KV 缓存复用；
        // 历史放进变化部分 —— 否则前缀一变，缓存就失效了。
        val sysPrefix = "<|im_start|>system\n" + sysAgent + "<|im_end|>\n"
        val prompt = buildString {
            // v8.1：Agent 每轮独立判断，不带历史 —— 历史会让每轮 prefill 越来越长
            // （实测 18→20→24 秒的递增就是它 + 缓存失效共同造成的）。
            append("<|im_start|>user\n[现在 ").append(nowStr).append("] ").append(msg).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            append("<think>\n\n</think>\n")
        }
        genStatus.value = GenStatus(phase = "处理输入")
        viewModelScope.launch(Dispatchers.IO) {
            val raw = StringBuilder()
            val t0 = System.currentTimeMillis()
            var firstTokAt = 0L
            var tokCount = 0
            // v10.41：路由页限制输出长度。
            // 一条动作指令不超过 15 个 token，给 128 已经很宽松；
            // 之前沿用全局的 2048，模型一旦啰嗦（复读提示词、自我解释），
            // 5 tok/s × 几百个 token 就是几十秒的纯浪费。
            val routeMaxTok = minOf(s.maxTokens, 128)
            val worker = Thread(null, Runnable {
                Llama.generate(sysPrefix, prompt, s.temperature, s.topK, s.topP, s.repeatPenalty, routeMaxTok) {
                    raw.append(it)
                    tokCount++
                    if (firstTokAt == 0L) firstTokAt = System.currentTimeMillis()
                    val (answer, thinking) = extractAnswer(raw.toString())
                    val show = if (thinking) "（思考中…）" else answer
                    val sec = (System.currentTimeMillis() - firstTokAt) / 1000.0
                    val speed = if (sec > 0.05) (tokCount / sec).toFloat() else 0f
                    val snap = GenStatus(if (thinking) "思考中" else "生成中", speed, tokCount, show.length)
                    mainHandler.post {
                        // v10.4：路由页不实时显示模型原文。
                        // 模型（尤其 1.5B）会把提示词、用户的话复读一遍，
                        // 那堆东西对用户毫无意义 —— 这里只更新状态条，
                        // 真正的结果等解析完（动作/云端/回答）再一次性给出。
                        genStatus.value = snap
                    }
                }
            }, "llama-gen")   // v2.2: 回退自定义栈大小(32MB)，用默认栈
            worker.start()
            while (worker.isAlive) {
                val el = (System.currentTimeMillis() - t0) / 1000.0
                val cur = genStatus.value
                if (cur.phase == "处理输入") {
                    genStatus.value = cur.copy(elapsedSec = el)
                }
                delay(120)
            }
            worker.join()
            mainHandler.post { genStatus.value = genStatus.value.copy(phase = "完成") }
            // 解析路由指令
            // v10.6 关键修复：解析对象必须是模型真正输出的文本（raw），
            // 不能是 ai.content.value —— 从 v10.4 起界面内容在流式阶段是空的，
            // 拿它去解析永远得到"直接回答"，动作全部不执行（血泪教训）。
            val finalText = extractAnswer(raw.toString()).first
            val allActions = Router.parseActions(finalText)
            when (val route = Router.parse(finalText)) {
                is RouteResult.Cloud -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 已路由云端] ${route.description}\n" }
                    val cloud = CloudClient.chat(s.apiBase, s.deepseekKey, route.description)
                    mainHandler.post { ai.content.value += cloud }
                }
                is RouteResult.Action -> {
                    val app = getApplication<android.app.Application>()
                    val todo = if (allActions.isNotEmpty()) allActions else listOf(route)
                    val sb = StringBuilder()
                    for (act in todo) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append("[→ 动作] ").append(act.name)
                        act.arg?.let { sb.append(" (").append(it).append(")") }
                        sb.append("\n")
                        var out = Actions.run(app, act.name, act.arg)
                        // v10.3：模型会把口语应用名翻译坏（实测「bli站」→「Bli Station」）。
                        // 名字对不上时别信模型，回头用用户原话再认一次。
                        if (out.startsWith("[找不到应用]") && act.name == "openapp") {
                            val hit = AppIndex.find(app, msg)
                            if (hit != null && AppIndex.launch(app, hit.pkg)) out = "已为你打开 ${hit.label}。"
                        }
                        sb.append(out)
                    }
                    val text = sb.toString()
                    mainHandler.post { ai.content.value = text }
                }
                is RouteResult.Exec -> {
                    mainHandler.post { ai.content.value += "\n\n[→ 执行命令] ${route.command}\n" }
                    val out = Executor.run(route.command, s.allowDangerous)
                    mainHandler.post { ai.content.value += out }
                }
                else -> {
                    // 直接回答：这时候才把模型写的东西一次显示出来
                    val app = getApplication<android.app.Application>()
                    val trimmed = finalText.trim()
                    // v10.6：模型有时只回一个应用名（实测问"能刷短视频的是哪个"→ 回「抖音」），
                    // 没带 [ACTION:openapp] 格式。长度很短且能在应用表里认出来，就当打开处理。
                    val bare = trimmed.takeIf { it.length in 1..10 && !it.contains("[") }
                    val hit = bare?.let { AppIndex.find(app, it) }
                    if (hit != null && AppIndex.launch(app, hit.pkg)) {
                        mainHandler.post { ai.content.value = "[→ 动作] openapp（模型只回了应用名）\n已为你打开 ${hit.label}。" }
                    } else {
                        mainHandler.post { ai.content.value = trimmed }
                    }
                }
            }
        }
    }

    fun updateSettings(s: AgentSettings) {
        // 线程数只在创建 context 时生效，改了不重新加载等于没改
        // （实测用户设 1 线程，日志里仍是 6，白测一轮）
        val oldThreads = settings.value.threads
        settings.value = s
        store.save(s)
        if (s.threads != oldThreads && modelLoaded.value) {
            status.value = "线程数已改为 ${s.threads} —— 必须点「加载模型」才生效（当前跑的还是旧值）"
        }
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
