package com.example.bonsaiagent.agent

import java.util.concurrent.TimeUnit

/**
 * 本机命令执行器。
 *
 * 安全设计（v7.0 重写）
 * ---------------------
 * 本地 8B 模型本身不可靠，可能被"帮我清理一下存储"这类话诱导出破坏性命令。
 * 原来的黑名单只有 10 个字符串，`rm -rf ~`、`rm -rf /sdcard/*`、`pm uninstall`、
 * `curl xx | sh` 全都拦不住，而且加个空格就能绕过。
 *
 * 现在改成三层：
 *   1) DANGEROUS —— 明确破坏性/提权/远程执行，默认拦截，需在设置里显式开启才放行
 *   2) SENSITIVE —— 写操作/网络下载，默认允许但会在结果里标注
 *   3) 其余 —— 直接执行
 *
 * 另外无论哪一层，执行记录都会写进最近 20 条历史，方便回查模型到底想做什麼。
 */
object Executor {

    /** 明确危险：删除、格式化、提权、卸载、关机、管道执行远程脚本 */
    private val DANGEROUS = listOf(
        // 删除：rm 带 -r 或指向根/家目录/通配符
        Regex("(^|[\\s;&|])rm\\s+(-[^\\s]*\\s+)*.*(-[rR]|--recursive)"),
        Regex("(^|[\\s;&|])rm\\s+(-[^\\s]*\\s+)*(/|~|\\*|/\\*)"),
        Regex("(^|[\\s;&|])rmdir\\s+(-[^\\s]*\\s+)*/"),
        // 磁盘/文件系统
        Regex("(^|[\\s;&|])(mkfs|mke2fs|fdisk|parted)\\b"),
        Regex("(^|[\\s;&|])dd\\s+.*\\bof="),
        Regex("(^|[\\s;&|])format\\b"),
        Regex(">\\s*/dev/"),
        // 提权
        Regex("(^|[\\s;&|])(su|sudo)\\b"),
        // 包管理 / 系统控制
        Regex("(^|[\\s;&|])pm\\s+(uninstall|clear|disable)"),
        Regex("(^|[\\s;&|])am\\s+(force-stop|kill)"),
        Regex("(^|[\\s;&|])(reboot|shutdown|poweroff|halt)\\b"),
        // 远程脚本直接执行
        Regex("(curl|wget|nc)\\b[^\\n]*\\|\\s*(sh|bash|python|node)"),
        // 其他高危
        Regex("(^|[\\s;&|])wipe\\b"),
        Regex("(^|[\\s;&|])chmod\\s+(-[^\\s]*\\s+)*777\\s+/"),
        Regex(":\\(\\)\\s*\\{")   // fork 炸弹
    )

    /** 敏感但常见：写文件、改权限、下载。允许执行，但标注一下 */
    private val SENSITIVE = listOf(
        Regex("(^|[\\s;&|])rm\\b"),
        Regex("(^|[\\s;&|])(mv|cp)\\b"),
        Regex("(^|[\\s;&|])chmod\\b"),
        Regex("(^|[\\s;&|])(curl|wget)\\b"),
        Regex("(^|[\\s;&|])pm\\s+install"),
        Regex(">\\s*")
    )

    /** 最近执行记录，供界面回查 */
    private val history = ArrayDeque<String>()

    fun historySnapshot(): List<String> = history.toList()

    private fun record(line: String) {
        history.addLast(line)
        while (history.size > 20) history.removeFirst()
    }

    fun classify(cmd: String): String? {
        for (r in DANGEROUS) if (r.containsMatchIn(cmd)) return r.pattern
        return null
    }

    fun isSensitive(cmd: String): Boolean = SENSITIVE.any { it.containsMatchIn(cmd) }

    /**
     * @param allowDangerous 设置页的"允许危险命令"开关，默认 false
     */
    fun run(command: String, allowDangerous: Boolean = false): String {
        val cmd = command.trim()
        if (cmd.isBlank()) return "[空命令]"

        val hit = classify(cmd)
        if (hit != null) {
            record("[拦截] $cmd")
            if (!allowDangerous) {
                return "[⚠️ 安全墙已拦截]\n$cmd\n\n" +
                        "这条命令可能删除数据、修改系统或执行远程脚本。\n" +
                        "如果你确认要跑，请到「设置 → 允许执行危险命令」打开开关后重试。\n" +
                        "（命中规则：$hit）"
            }
            record("[放行·危险] $cmd")
        } else if (isSensitive(cmd)) {
            record("[执行·敏感] $cmd")
        } else {
            record("[执行] $cmd")
        }

        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            // 必须并发读，否则大输出会把管道撑满导致卡死
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val tOut = Thread { try { proc.inputStream.bufferedReader().forEachLine { outSb.appendLine(it) } } catch (_: Throwable) {} }
            val tErr = Thread { try { proc.errorStream.bufferedReader().forEachLine { errSb.appendLine(it) } } catch (_: Throwable) {} }
            tOut.start(); tErr.start()
            val finished = proc.waitFor(15, TimeUnit.SECONDS)
            if (!finished) proc.destroy()
            tOut.join(500); tErr.join(500)

            val sb = StringBuilder()
            if (outSb.isNotBlank()) sb.append(outSb)
            if (errSb.isNotBlank()) sb.append("\n[stderr]\n").append(errSb)
            if (!finished) sb.append("\n[超时已终止]")
            if (sb.isBlank()) sb.append("[无输出]")
            sb.toString().take(4000)
        } catch (e: Throwable) {
            "[执行异常] ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
