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
    val allowDangerous: Boolean = false
)

const val DEFAULT_SYSTEM_PROMPT = """无 root、无 adb。只输出一行，不解释：
操作手机→[ACTION:名] 名可: time battery gallery camera settings apps wifi dialer browser
联网或复杂→[CALL_CLOUD] 需求
其他→直接回答
禁止 [EXEC:] 和 shell 命令。"""

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
            putString("systemPrompt", s.systemPrompt)
            putString("downloadSource", s.downloadSource)
            putBoolean("apiEnabled", s.apiEnabled)
            putString("deepseekKey", s.deepseekKey)
            putString("apiBase", s.apiBase)
            putBoolean("allowDangerous", s.allowDangerous)
        }.apply()
    }
}
