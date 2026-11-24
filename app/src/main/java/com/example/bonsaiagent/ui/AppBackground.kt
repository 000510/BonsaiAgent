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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
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
 *
 * v10.148：纯色背景自动叠加「同色系光斑」。
 * 光斑只把用户选的颜色亮度推 ±8% 左右，色相不变：页面观感几乎不变，
 * 但玻璃边缘立刻有东西可掰弯、磨砂也糊得出明暗 —— 这是「玻璃感」的物理前提。
 * 资料包 06 的一句话结论：毛玻璃要好看，关键不在玻璃本身，而在玻璃后面要有东西可以模糊。
 * bgType=image 时完全不动（图片本身就有细节）。
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
        "color" -> {
            val c = Color(s.bgColor)
            Box(modifier.fillMaxSize().background(c).flatColorBlobs(c))
        }
        else -> Box(modifier.fillMaxSize().background(fallback).flatColorBlobs(fallback))
    }
}

/**
 * v10.148：同色系光斑层。在纯色底上画 4 团超大半径的径向渐变，
 * 颜色 = 用户颜色向白/黑各 lerp 10%，再乘 0.55 透明度 → 实际亮度波动约 ±5%，
 * 肉眼看仍是「干净的纯色页面」，但玻璃的边缘折射 / 磨砂明暗立刻可见。
 */
private fun Modifier.flatColorBlobs(base: Color): Modifier = drawBehind { drawBlobs(base) }

private fun DrawScope.drawBlobs(base: Color) {
    val w = size.width
    val h = size.height
    val light = lerp(base, Color.White, 0.10f)
    val dark = lerp(base, Color.Black, 0.10f)
    // (中心x比例, 中心y比例, 半径比例, 颜色, 透明度)
    // 注意：暗斑避开玻璃中心区域（弹窗在底部），否则玻璃中间会显出一团脏灰色
    val blobs = listOf(
        Blob(w * 0.20f, h * 0.26f, w * 0.58f, light, 0.55f),
        Blob(w * 0.92f, h * 0.30f, w * 0.50f, dark, 0.45f),
        Blob(w * 0.55f, h * 0.88f, w * 0.62f, light, 0.60f),
        Blob(w * 0.06f, h * 0.70f, w * 0.44f, dark, 0.45f)
    )
    blobs.forEach { b ->
        val center = Offset(b.cx, b.cy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(b.color.copy(alpha = b.alpha), Color.Transparent),
                center = center,
                radius = b.r
            ),
            radius = b.r,
            center = center
        )
    }
}

private data class Blob(val cx: Float, val cy: Float, val r: Float, val color: Color, val alpha: Float)

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
