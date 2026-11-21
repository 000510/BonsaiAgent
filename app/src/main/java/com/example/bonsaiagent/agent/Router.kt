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
     * v10.112：容错归一化 —— 把模型常见的"格式错字"纠正成标准格式。
     * 目标：用户看不到错误，指令照常执行。
     */
    fun normalizeOutput(raw: String): String {
        var s = raw
        // 1) 全角括号 → 半角
        s = s.replace('［', '[').replace('］', ']')
            .replace('（', '(').replace('）', ')')
        // 2) 全角冒号 → 半角
        s = s.replace('：', ':')
        // 3) ACTION 的各种错写：数字0冒充O、数字1冒充I/l、全角字母
        //    先全角字母转半角（Ａ→A ...）
        val sb = StringBuilder()
        for (ch in s) {
            val c = if (ch.code in 0xFF21..0xFF3A) (ch.code - 0xFF21 + 'A'.code).toChar() // 全角大写
                    else if (ch.code in 0xFF41..0xFF5A) (ch.code - 0xFF41 + 'a'.code).toChar() // 全角小写
                    else ch
            sb.append(c)
        }
        s = sb.toString()
        // 4) 统一大小写后纠正混淆字符：ACTI0N / ACTlON / ACT1ON → ACTION
        s = s.replace(Regex("(?i)acti[0l1]n"), "ACTION")
            .replace(Regex("(?i)call[0l1]_?cloud"), "CALL_CLOUD")
            .replace(Regex("(?i)exec"), "EXEC")
        // 5) 常见的中文动作标签 → 标准英文（模型偶尔按语义翻译）
        s = s.replace(Regex("(?i)\\[动作[:：]"), "[ACTION:")
        return s
    }

    /**
     * v10.6：解析出**所有**动作。
     *
     * 实测用户问「现在几点顺便说下还有多少电」，模型会正确地输出
     * `[ACTION:time] [ACTION:battery]` 两个动作 —— 但老的 parse 只取第一个，
     * 第二个被白白丢掉。这里全部取出来，让调用方逐个执行。
     */
    fun parseActions(output: String): List<RouteResult.Action> {
        val out = ArrayList<RouteResult.Action>()
        val normalized = normalizeOutput(output)   // v10.112：先纠正错字
        for (m in ACTION_RE.findAll(normalized)) {
            val body = m.groupValues[1].trim()
            val sep = body.indexOfFirst { it == ' ' || it == '\u3000' || it == ':' || it == '\t' }
            val name = (if (sep >= 0) body.substring(0, sep) else body).trim().lowercase()
            val argIn = if (sep >= 0) body.substring(sep + 1).trim() else ""
            val tail = normalized.substring(m.range.last + 1)
                .lineSequence().firstOrNull()?.trim().orEmpty()
                .takeIf { it.length in 1..20 && it.none { c -> c in "[]" } } ?: ""
            val arg = argIn.ifBlank { tail }.ifBlank { null }
            if (name.isNotEmpty()) out.add(RouteResult.Action(name, arg))
        }
        return out
    }

    fun parse(output: String): RouteResult {
        val normalized = normalizeOutput(output)   // v10.112：先纠正错字
        // ACTION 优先：它比 EXEC 可靠得多，别被后面的 EXEC 抢走
        ACTION_RE.find(normalized)?.let { m ->
            val body = m.groupValues[1].trim()
            val sep = body.indexOfFirst { it == ' ' || it == '\u3000' || it == ':' || it == '\t' }
            val name = (if (sep >= 0) body.substring(0, sep) else body).trim().lowercase()
            val argIn = if (sep >= 0) body.substring(sep + 1).trim() else ""

            // 括号外紧跟的那一小段也可能是应用名：「[ACTION:openapp] 微信」
            val tail = normalized.substring(m.range.last + 1)
                .lineSequence().firstOrNull()?.trim().orEmpty()
                .takeIf { it.length in 1..20 && it.none { c -> c in "[]" } }
                ?: ""

            val arg = argIn.ifBlank { tail }.ifBlank { null }
            if (name.isNotEmpty()) return RouteResult.Action(name, arg)
        }
        if (normalized.contains("[CALL_CLOUD]")) {
            val desc = normalized.substringAfter("[CALL_CLOUD]").trim()
            return RouteResult.Cloud(desc)
        }
        EXEC_RE.find(normalized)?.let { m ->
            return RouteResult.Exec(m.groupValues[1].trim())
        }
        return RouteResult.Local
    }
}
