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
fun AppBackground(
    s: AgentSettings,
    modifier: Modifier = Modifier,
    maxSide: Int = 1440
) {
    val fallback = Color(0xFFF7F8FC)
    when (s.bgType) {
        "image" -> {
            val path = s.bgImagePath
            val bmp = remember(path, maxSide) { decodeWallpaper(path, maxSide) }
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
    // v10.149：暗斑全部避开底部弹窗区域 —— 玻璃内部会映出背景的明暗，
    // 暗斑压在弹窗下面就会在玻璃里显出一块「不一样颜色的区域」
    val blobs = listOf(
        Blob(w * 0.20f, h * 0.24f, w * 0.58f, light, 0.55f),
        Blob(w * 0.92f, h * 0.26f, w * 0.50f, dark, 0.40f),
        Blob(w * 0.55f, h * 0.86f, w * 0.62f, light, 0.55f),
        Blob(w * 0.06f, h * 0.52f, w * 0.42f, light, 0.30f)
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

/** 降采样解码壁纸（手机照片动辄 4000px，直接解码会吃光内存）。maxSide：
 *  主背景用 1440；玻璃卡片的镜像层用更小的值（只占卡片大小，避免双份大图 OOM） */
// v10.171：原来的 private 改 internal —— 玻璃卡片的 CPU 糊化副本要用同一个解码器。
internal fun decodeWallpaper(path: String, maxSide: Int = 1440) = runCatching {
    if (path.isBlank()) return@runCatching null
    val f = File(path)
    if (!f.exists() || f.length() <= 0L) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
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

/**
 * v10.158：背景平均亮度（0=黑 / 1=白），给玻璃卡片做「自动文字配色」。
 *  - color：直接由色值算 luma
 *  - image：把图降采样到 ~48px 宽再遍历取平均（一次性，remember 缓存）
 *  - 其它：走 AppBackground 的 fallback 浅色
 * 返回 0.55 以上视为「亮背景」→ 深色字；否则视为「暗背景」→ 白字。
 */
@Composable
fun rememberBackgroundLuma(s: AgentSettings): Float = remember(s.bgType, s.bgColor, s.bgImagePath) {
    when (s.bgType) {
        "color" -> {
            val c = Color(s.bgColor)
            0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        }
        "image" -> sampleImageLuma(s.bgImagePath)
        else -> {
            val c = Color(0xFFF7F8FC)
            0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        }
    }
}

/** 降采样到 ~48px 宽后遍历像素求平均亮度（2304 次以内，毫秒级）。
 *  v10.159：只采「下半部分」——液态玻璃气泡固定在屏幕底部，
 *  整图平均亮度（含上方蓝天）会明显高于卡片背后的实际区域，
 *  导致「深背景低磨砂」判不中（实测湖水 luma=0.47 而整图≈0.6）。 */
private fun sampleImageLuma(path: String): Float = runCatching {
    if (path.isBlank()) return@runCatching 0.95f
    val f = File(path)
    if (!f.exists() || f.length() <= 0L) return@runCatching 0.95f
    val b0 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b0)
    if (b0.outWidth <= 0) return@runCatching 0.95f
    var sample = 1
    while (b0.outWidth / sample > 48) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@runCatching 0.95f
    var sum = 0.0
    var n = 0
    val y0 = (bmp.height * 0.55f).toInt().coerceIn(0, bmp.height - 1)
    for (y in y0 until bmp.height) {
        for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            sum += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
            n++
        }
    }
    bmp.recycle()
    if (n == 0) 0.95f else (sum / n / 255.0).toFloat()
}.getOrDefault(0.95f)
