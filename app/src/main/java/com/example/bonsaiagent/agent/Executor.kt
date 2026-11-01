package com.example.bonsaiagent.agent

import java.util.concurrent.TimeUnit

/**
 * 本机命令执行器。仅允许通过 Runtime.exec 跑 shell，并加了一道安全墙：
 * 命中危险命令片段直接拦截，避免模型被诱导执行破坏性操作。
 */
object Executor {
    // 简单黑名单：命中即拦截（可按需增补）
    private val BLOCKLIST = listOf(
        "rm -rf /", "rm -rf /*", "mkfs", "dd if=", "format ",
        ">: /dev/", "chmod -R 777 /", ":(){", "shutdown", "reboot"
    )

    fun run(command: String): String {
        val cmd = command.trim()
        if (cmd.isBlank()) return "[空命令]"
        if (BLOCKLIST.any { cmd.contains(it, ignoreCase = true) }) {
            return "[安全策略已拦截] $cmd"
        }
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val finished = proc.waitFor(15, TimeUnit.SECONDS)
            val sb = StringBuilder()
            if (out.isNotBlank()) sb.append(out)
            if (err.isNotBlank()) sb.append("\n[stderr]\n").append(err)
            if (!finished) { proc.destroy(); sb.append("\n[超时已终止]") }
            sb.toString().take(4000)
        } catch (e: Exception) {
            "[执行异常] ${e.message}"
        }
    }
}
