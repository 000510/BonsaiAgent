package com.example.bonsaiagent.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 混合 Agent 的全部可调参数。默认值对齐你的需求：
 * 最大输出 2048 / 温度 0.30 / Top-K 40 / Top-P 0.95 / 重复惩罚 1.00 /
 * 上下文 8192 / 推理线程 8。
 */
data class AgentSettings(
    val maxTokens: Int = 2048,
    val temperature: Float = 0.70f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.10f,
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val downloadSource: String = "HF镜像",
    val apiEnabled: Boolean = false,
    val deepseekKey: String = "",
    val apiBase: String = "https://api.deepseek.com",
    // v7.0：危险命令开关。默认关闭 —— 本地模型可能被诱导出破坏性命令，
    // 必须由用户明确打开才允许执行 rm -rf / pm uninstall / su 这类操作。
    val allowDangerous: Boolean = false,

    // v10.20：LiteRT 后端（NPU / GPU / CPU）。只在加载 .litertlm 模型时生效。
    val litertBackend: String = "NPU",

    // v10.72：投机解码。草稿模型跟主模型共用词表（Qwen3 全系），
    // 主模型一次前向验证 γ 个候选，把「读权重的带宽」摊薄，实测能提速 1.4~1.8 倍。
    val useDraft: Boolean = false,
    val draftPath: String = "",
    val draftMax: Int = 3,
    // v10.94：n-gram 自投机（零额外内存，默认开）
    val useNgram: Boolean = true,
    val ngramN: Int = 4,
    val ngramGamma: Int = 8,

    // v10.83：界面配色。每个预设一个种子色，theme.kt 按种子推导整套明暗色板。
    val themePreset: String = "Moss",
    // v10.94：自定义配色（themePreset=="Custom" 时生效），存 ARGB Long
    val customSeed: Long = 0xFF2E7D58,
    val customAccent: Long = 0xFFB87B32,

    // v10.85：上次成功加载的主模型路径。打开 App 时优先恢复它，
    // 不再靠「猜」——之前按最新导入或体积最大猜，都会误选到别的模型。
    val lastModelPath: String = ""
)

const val DEFAULT_SYSTEM_PROMPT = """你是手机助手，只回一行中文，不要复述、不要夹英文。
查时间[ACTION:time]、电量[ACTION:battery]、开应用[ACTION:openapp]应用名、打电话[ACTION:dialer]号码、开相机[ACTION:camera]、开相册[ACTION:gallery]、开设置[ACTION:settings]、开浏览器[ACTION:browser]网址、复制[ACTION:copy]文字、朗读[ACTION:speak]文字、分享[ACTION:share]文字。
需要联网/查最新信息写[CALL_CLOUD]具体需求。普通问题直接用中文简短回答。"""

class SettingsStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("bonsai_settings", Context.MODE_PRIVATE)

    fun load(): AgentSettings {
        // v4.3 参数迁移：
        // 旧的默认值是 温度0.30 / 重复惩罚1.00 —— 对 0.5B 这种小模型太保守，
        // 表现为回答呆板、反复说同一句。只要用户没手动改过（还是旧默认值），就升到新默认。
        // v7.2：旧的 system prompt 教模型编 shell 命令（实测会输出 gallery、adb shell pm 这类
        // 根本跑不了的东西）。如果用户没改过（仍是旧文案），换成新的动作清单。
        if (sp.getInt("prefsVersion", 1) < 3) {
            val e = sp.edit()
            if (sp.getFloat("temperature", 0.30f) <= 0.31f) e.putFloat("temperature", 0.70f)
            if (sp.getFloat("repeatPenalty", 1.00f) <= 1.001f) e.putFloat("repeatPenalty", 1.10f)
            e.apply()
        }
        if (sp.getInt("prefsVersion", 1) < 6) {
            val oldPrompt = sp.getString("systemPrompt", "") ?: ""
            // 命中任一旧版特征就换新文案（用户自己改过的不会命中，保持原样）
            val isOld = oldPrompt.isEmpty() ||
                    oldPrompt.contains("本地混合智能体") ||   // v7.0 及更早
                    oldPrompt.contains("可用动作：") ||        // v7.2 的长清单
                    oldPrompt.contains("禁止输出 [EXEC:]")    // v7.3
            if (isOld) sp.edit().putString("systemPrompt", DEFAULT_SYSTEM_PROMPT).apply()
            sp.edit().putInt("prefsVersion", 6).apply()
        }
        // v10.0：动作协议从「协议名清单」改成「开应用 + 参数」。
        // 1.5B 会照着「操作手机→[ACTION:名]」这个范例原样复读，
        // 所以新文案里一个具体例子都不给，只留占位符。
        if (sp.getInt("prefsVersion", 1) < 12) {
            val oldPrompt = sp.getString("systemPrompt", "") ?: ""
            val isOld = oldPrompt.isEmpty() ||
                    oldPrompt.contains("操作手机→[ACTION:名]") ||
                    oldPrompt.contains("联网或复杂→[CALL_CLOUD] 需求") ||
                    oldPrompt.contains("开应用→[ACTION:openapp]") ||
                    oldPrompt.contains("查状态→[ACTION:time]") ||
                    oldPrompt.contains("你是手机助手") ||
                    oldPrompt.contains("要打开某个App") ||
                    oldPrompt.contains("开App：[ACTION:openapp]") ||
                    oldPrompt.contains("开App写[ACTION:openapp]名字。时间写")
            if (isOld) sp.edit().putString("systemPrompt", DEFAULT_SYSTEM_PROMPT).apply()
            sp.edit().putInt("prefsVersion", 12).apply()
        }
        // v10.74：γ 默认从 6 降到 3。
        // 实测日志 058：γ=8、接受率 24% 时只有 1.71 tok/s，比不开还慢 6 倍。
        // 原因是手机算 Output 层（15 万词表）代价极高，每多验证一个位置就多一次
        // 完整的前向输出层。手机上 γ=2~3 才是甜点。
        if (sp.getInt("prefsVersion", 1) < 13) {
            sp.edit().putInt("draftMax", 3).putInt("prefsVersion", 13).apply()
        }
        // v10.108：升级系统提示词（加新动作说明）。只替换仍是旧文案的用户，
        // 自己改过提示词的保持原样。
        if (sp.getInt("prefsVersion", 1) < 14) {
            val oldPrompt = sp.getString("systemPrompt", "") ?: ""
            if (oldPrompt.contains("开App写[ACTION:openapp]名字") ||
                oldPrompt == DEFAULT_SYSTEM_PROMPT) {
                sp.edit().putString("systemPrompt", DEFAULT_SYSTEM_PROMPT).apply()
            }
            sp.edit().putInt("prefsVersion", 14).apply()
        }
        // v10.110：去掉手电筒动作（不需要且不可用），更新提示词。只替换仍含手电筒的旧文案。
        if (sp.getInt("prefsVersion", 1) < 15) {
            val oldPrompt = sp.getString("systemPrompt", "") ?: ""
            if (oldPrompt.contains("手电筒[ACTION:flashlight]") ||
                oldPrompt.contains("分享[ACTION:share]文字、手电筒")) {
                sp.edit().putString("systemPrompt", DEFAULT_SYSTEM_PROMPT).apply()
            }
            sp.edit().putInt("prefsVersion", 15).apply()
        }
        return loadInner()
    }

    private fun loadInner(): AgentSettings = AgentSettings(
        maxTokens = sp.getInt("maxTokens", 2048),
        temperature = sp.getFloat("temperature", 0.70f),
        topK = sp.getInt("topK", 40),
        topP = sp.getFloat("topP", 0.95f),
        repeatPenalty = sp.getFloat("repeatPenalty", 1.10f),
        contextSize = sp.getInt("contextSize", 4096),
        threads = sp.getInt("threads", 4),
        systemPrompt = sp.getString("systemPrompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT,
        downloadSource = sp.getString("downloadSource", "HF镜像") ?: "HF镜像",
        litertBackend = sp.getString("litertBackend", "NPU") ?: "NPU",
        useDraft = sp.getBoolean("useDraft", false),
        draftPath = sp.getString("draftPath", "") ?: "",
        draftMax = sp.getInt("draftMax", 3),
        useNgram = sp.getBoolean("useNgram", true),
        ngramN = sp.getInt("ngramN", 4),
        ngramGamma = sp.getInt("ngramGamma", 8),
        themePreset = sp.getString("themePreset", "Moss") ?: "Moss",
        customSeed = sp.getLong("customSeed", 0xFF2E7D58),
        customAccent = sp.getLong("customAccent", 0xFFB87B32),
        lastModelPath = sp.getString("lastModelPath", "") ?: "",
        apiEnabled = sp.getBoolean("apiEnabled", false),
        deepseekKey = sp.getString("deepseekKey", "") ?: "",
        apiBase = sp.getString("apiBase", "https://api.deepseek.com") ?: "https://api.deepseek.com",
        allowDangerous = sp.getBoolean("allowDangerous", false)
    )

    fun save(s: AgentSettings) {
        sp.edit().apply {
            putInt("maxTokens", s.maxTokens)
            putFloat("temperature", s.temperature)
            putInt("topK", s.topK)
            putFloat("topP", s.topP)
            putFloat("repeatPenalty", s.repeatPenalty)
            putInt("contextSize", s.contextSize)
            putInt("threads", s.threads)
            putString("litertBackend", s.litertBackend)
            putBoolean("useDraft", s.useDraft)
            putString("draftPath", s.draftPath)
            putInt("draftMax", s.draftMax)
            putBoolean("useNgram", s.useNgram)
            putInt("ngramN", s.ngramN)
            putInt("ngramGamma", s.ngramGamma)
            putString("themePreset", s.themePreset)
            putLong("customSeed", s.customSeed)
            putLong("customAccent", s.customAccent)
            putString("lastModelPath", s.lastModelPath)
            putString("systemPrompt", s.systemPrompt)
            putString("downloadSource", s.downloadSource)
            putBoolean("apiEnabled", s.apiEnabled)
            putString("deepseekKey", s.deepseekKey)
            putString("apiBase", s.apiBase)
            putBoolean("allowDangerous", s.allowDangerous)
        }.apply()
    }
}
