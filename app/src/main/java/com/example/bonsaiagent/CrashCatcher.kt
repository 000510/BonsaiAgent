package com.example.bonsaiagent

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃兜底：把未捕获异常的完整堆栈写两份。
 *
 * 为什么要这个：手机上闪退时看不到 logcat，App 又是 debuggable 的 Debug 包，
 * 崩溃信息一带而过就没法定位。这里落盘到
 *   ① files/logs/ui_crash.txt      —— 下次启动 App 自己读
 *   ② 系统 Download/bonsai_crash.txt —— 用文件管理器直接能看到（Android 10+ 免权限）
 */
object CrashCatcher {

    const val FILE_NAME = "bonsai_crash.txt"
    const val BOOT_NAME = "bonsai_boot.txt"

    /**
     * 启动流水埋点：每一步都写一行。
     *
     * 黑屏时最需要知道的是「卡在哪一步」，而不是「崩在哪一行」——
     * 卡住不会产生异常，也就没有堆栈。于是把关键节点全记下来：
     * 用户把 Download/bonsai_boot.txt 发来，就能看到最后一条停在哪。
     */
    fun mark(ctx: Context, msg: String) {
        runCatching {
            val line = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date()) + "  " + msg + "\n"
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            File(dir, "boot_trace.txt").appendText(line)
            writeDownloads(ctx, BOOT_NAME, File(dir, "boot_trace.txt").readText(), append = true, newLine = line)
        }
    }

    /** 写系统 Download 目录（Android 10+ 走 MediaStore，免权限） */
    private fun writeDownloads(ctx: Context, name: String, text: String, append: Boolean = false, newLine: String? = null) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
            // 每次都重建，避免重复追加
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name)
            )
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(text.toByteArray())
                }
            }
        }
    }

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("thread: ${t?.name}")
                    appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
                    appendLine("----- stack -----")
                    append(sw.toString())
                }

                // ① 应用私有目录
                runCatching {
                    val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
                    File(dir, "ui_crash.txt").writeText(text)
                }

                // ② 系统下载目录（Android 10+ 走 MediaStore，不需要存储权限）
                writeDownloads(ctx, FILE_NAME, text)
            }
            prev?.uncaughtException(t, e)
        }
    }
}
