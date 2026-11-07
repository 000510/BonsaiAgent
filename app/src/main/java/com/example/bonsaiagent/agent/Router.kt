package com.example.bonsaiagent.agent

/**
 * 混合路由解析：从本地模型的输出里识别特殊指令。
 *
 * 三种结果
 *  - [CALL_CLOUD] 描述   → 交给云端（DeepSeek）
 *  - [EXEC: 命令]        → 在本机跑 shell（能力很有限，见 Actions 注释）
 *  - [ACTION: 名字]      → 触发 App 内预置动作（打开相册/相机等）
 *
 * 为什么要有 ACTION
 * -----------------
 * 实测让模型自己编 shell 命令，它会输出 `gallery`、`adb shell pm ...` 这类
 * Android 上根本不存在或没权限的东西。普通 App 的 shell 用不了 am/pm
 *（那是 shell 用户才有的权限），所以「打开相册」在 shell 层面无解。
 * 正确做法是由 App 提供一组固定动作，模型只负责从里面挑一个。
 */
sealed class RouteResult {
    object Local : RouteResult()
    data class Cloud(val description: String) : RouteResult()
    data class Exec(val command: String) : RouteResult()
    data class Action(val name: String, val arg: String?) : RouteResult()
}

object Router {
    private val EXEC_RE = Regex("\\[EXEC:\\s*(.+?)\\s*\\]", RegexOption.DOT_MATCHES_ALL)
    // v10.0：括号内允许中文和空格 —— [ACTION:openapp 微信] / [ACTION:相册]
    // 同时兼容老格式 [ACTION:gallery]（参数写在括号外）
    private val ACTION_RE = Regex("\\[ACTION:([^\\]]*)\\]")

    /**
     * v10.6：解析出**所有**动作。
     *
     * 实测用户问「现在几点顺便说下还有多少电」，模型会正确地输出
     * `[ACTION:time] [ACTION:battery]` 两个动作 —— 但老的 parse 只取第一个，
     * 第二个被白白丢掉。这里全部取出来，让调用方逐个执行。
     */
    fun parseActions(output: String): List<RouteResult.Action> {
        val out = ArrayList<RouteResult.Action>()
        for (m in ACTION_RE.findAll(output)) {
            val body = m.groupValues[1].trim()
            val sep = body.indexOfFirst { it == ' ' || it == '\u3000' || it == ':' || it == '\t' }
            val name = (if (sep >= 0) body.substring(0, sep) else body).trim().lowercase()
            val argIn = if (sep >= 0) body.substring(sep + 1).trim() else ""
            val tail = output.substring(m.range.last + 1)
                .lineSequence().firstOrNull()?.trim().orEmpty()
                .takeIf { it.length in 1..20 && it.none { c -> c in "[]" } } ?: ""
            val arg = argIn.ifBlank { tail }.ifBlank { null }
            if (name.isNotEmpty()) out.add(RouteResult.Action(name, arg))
        }
        return out
    }

    fun parse(output: String): RouteResult {
        // ACTION 优先：它比 EXEC 可靠得多，别被后面的 EXEC 抢走
        ACTION_RE.find(output)?.let { m ->
            val body = m.groupValues[1].trim()
            val sep = body.indexOfFirst { it == ' ' || it == '\u3000' || it == ':' || it == '\t' }
            val name = (if (sep >= 0) body.substring(0, sep) else body).trim().lowercase()
            val argIn = if (sep >= 0) body.substring(sep + 1).trim() else ""

            // 括号外紧跟的那一小段也可能是应用名：「[ACTION:openapp] 微信」
            val tail = output.substring(m.range.last + 1)
                .lineSequence().firstOrNull()?.trim().orEmpty()
                .takeIf { it.length in 1..20 && it.none { c -> c in "[]" } }
                ?: ""

            val arg = argIn.ifBlank { tail }.ifBlank { null }
            if (name.isNotEmpty()) return RouteResult.Action(name, arg)
        }
        if (output.contains("[CALL_CLOUD]")) {
            val desc = output.substringAfter("[CALL_CLOUD]").trim()
            return RouteResult.Cloud(desc)
        }
        EXEC_RE.find(output)?.let { m ->
            return RouteResult.Exec(m.groupValues[1].trim())
        }
        return RouteResult.Local
    }
}
