package com.example.bonsaiagent.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 模型导入工具：从系统文件选择器（SAF）拿到 .gguf 的 Uri，
 * 复制到 App 私有目录（llama.cpp 需要真实文件路径），返回本地路径。
 */
object ModelLoader {

    /** 复制 Uri 指向的文件到 app files 目录，返回目标路径；失败返回 null */
    fun importFromUri(context: Context, uri: Uri): String? {
        return try {
            val name = queryName(context, uri) ?: "model.gguf"
            val target = File(context.filesDir, "models/$name")
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            target.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 已导入模型的本地路径（如果存在） */
    fun currentModelPath(context: Context): String? {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) return null
        return dir.listFiles { f -> (f.extension.equals("gguf", true) || f.extension.equals("litertlm", true)) }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    private fun queryName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }
}
