package com.example.bonsaiagent.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.bonsaiagent.data.AgentSettings
import java.io.File

/**
 * v10.142：界面背景（聊天页 / Agent 页共用）。
 *
 * 为什么加这个：透明液态玻璃的原理是「1:1 复制背后的内容，只把边缘一圈掰弯」。
 * 界面是纯白的时候，复制白 = 白，掰弯白 = 白 —— 物理上就看不出玻璃。
 * iOS 的液态玻璃之所以好看，是因为它永远盖在壁纸/照片/彩色内容上。
 * 所以给用户一个能选背景图片或纯色的入口，玻璃才有东西可折射。
 */
@Composable
fun AppBackground(s: AgentSettings, modifier: Modifier = Modifier) {
    val fallback = Color(0xFFF7F8FC)
    when (s.bgType) {
        "image" -> {
            val path = s.bgImagePath
            val bmp = remember(path) { decodeWallpaper(path) }
            Box(modifier.fillMaxSize().background(fallback)) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        "color" -> Box(modifier.fillMaxSize().background(Color(s.bgColor)))
        else -> Box(modifier.fillMaxSize().background(fallback))
    }
}

/** 降采样解码壁纸（手机照片动辄 4000px，直接解码会吃光内存） */
private fun decodeWallpaper(path: String) = runCatching {
    if (path.isBlank()) return@runCatching null
    val f = File(path)
    if (!f.exists() || f.length() <= 0L) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    })
}.getOrNull()

/**
 * 把用户选中的图片复制到 App 私有目录，返回绝对路径。
 * 不存 Uri —— 系统相册 Uri 的读取授权重启后会失效，复制一份最稳。
 */
fun importBackgroundImage(context: Context, uri: Uri): String? = runCatching {
    val dst = File(context.filesDir, "bg_wallpaper.img")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dst.outputStream().use { out -> input.copyTo(out) }
    } ?: return@runCatching null
    if (dst.length() <= 0L) return@runCatching null
    dst.absolutePath
}.getOrNull()
