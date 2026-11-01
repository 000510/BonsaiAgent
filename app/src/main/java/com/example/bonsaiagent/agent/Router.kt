package com.example.bonsaiagent.agent

/**
 * 混合路由解析：从本地模型的输出里识别特殊指令。
 * - [CALL_CLOUD] 描述  -> 需要调用云端（DeepSeek）
 * - [EXEC: 命令]       -> 需要在本机执行 shell
 */
sealed class RouteResult {
    object Local : RouteResult()
    data class Cloud(val description: String) : RouteResult()
    data class Exec(val command: String) : RouteResult()
}

object Router {
    private val EXEC_RE = Regex("\\[EXEC:\\s*(.+?)\\s*\\]", RegexOption.DOT_MATCHES_ALL)

    fun parse(output: String): RouteResult {
        if (output.contains("[CALL_CLOUD]")) {
            val desc = output.substringAfter("[CALL_CLOUD]").trim()
            return RouteResult.Cloud(desc)
        }
        val m = EXEC_RE.find(output)
        if (m != null) {
            return RouteResult.Exec(m.groupValues[1].trim())
        }
        return RouteResult.Local
    }
}
