package com.example.bonsaiagent.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 模型文件管理：导入（SAF → 私有目录）、列出、删除。
 *
 * v10.22 加了完整性校验
 * ------------------
 * 实测踩过一次坑：2.33 GB 的模型只复制了 0.15 GB 就被返回"成功"，
 * 之后加载报 "tensor data is not within the file bounds, model is corrupted"。
 * 复制中断在 Android 上很常见（后台限制、IO 超时、存储瞬间不足），
 * 所以必须比对源文件大小和目标文件大小。
 */
object ModelLoader {

    @Volatile var lastError: String = ""
        private set

    /** 复制 Uri 指向的文件到 app files 目录，返回目标路径；失败返回 null */
    fun importFromUri(context: Context, uri: Uri): String? {
        lastError = ""
        return try {
            val name = queryName(context, uri) ?: "model.gguf"
            val dir = File(context.filesDir, "models")
            dir.mkdirs()
            val target = File(dir, name)

            // 同名残留先删掉，避免旧文件占空间 + 混淆
            if (target.exists()) target.delete()

            val expected = querySize(context, uri)

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            } ?: run {
                lastError = "打不开这个文件（权限或路径问题）"
                return null
            }

            val actual = target.length()

            // ★ 完整性校验：和源文件大小不一致 = 复制中断
            if (expected > 0 && actual != expected) {
                target.delete()
                lastError = "导入中断：应复制 ${fmt(expected)}，实际只有 ${fmt(actual)}"
                return null
            }
            // 再兜一层：小于 8MB 的东西不可能是能跑的模型
            if (actual < 8L * 1024 * 1024) {
                target.delete()
                lastError = "文件只有 ${fmt(actual)}，太小了，不像模型"
                return null
            }

            target.absolutePath
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            try { File(context.filesDir, "models").listFiles()?.forEach { } } catch (_: Throwable) {}
            null
        }
    }

    /** 私有目录里所有模型文件 */
    fun listModels(context: Context): List<File> {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            f.isFile && (f.extension.equals("gguf", true) || f.extension.equals("litertlm", true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteModel(file: File): Boolean = try {
        file.delete()
    } catch (_: Throwable) {
        false
    }

    /** 已导入模型的本地路径（取最新的） */
    fun currentModelPath(context: Context): String? =
        listModels(context).firstOrNull()?.absolutePath

    fun sizeText(bytes: Long): String = fmt(bytes)

    private fun fmt(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%d KB", bytes / 1024)
    }

    private fun queryName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    /** 源文件大小（SAF 提供，可能为 0 表示未知） */
    private fun querySize(context: Context, uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) size = c.getLong(idx)
        }
        return size
    }
}
