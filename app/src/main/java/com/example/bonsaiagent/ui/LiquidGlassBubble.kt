package com.example.bonsaiagent.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

import com.example.bonsaiagent.data.AgentSettings
import kotlin.math.roundToInt

/**
 * v10.157：液态玻璃卡片（根因修复版）。
 *
 * 根因（诊断开关版逐件排除后确认）：本机把 Modifier.shadow 渲染成了
 * 「内侧压暗」——本应只出现在卡片轮廓外的投影，把卡片左右边带各压暗
 * 约 9 个亮度。半透明磨砂透出背景时，中间区域比边带亮 ~13，看起来就是
 * 一块「位置固定的白色矩形」；不透明磨砂把它盖住，所以红卡/近不透明版
 * 看不到。与玻璃实现无关（四版都复现），与内容组件无关（全关仍在）。
 *
 * 修复：不用 Modifier.shadow，改为「环形手绘投影」——在卡片轮廓外侧
 * 一圈圈向外淡出的描边环（描边环互不重叠、也不进入卡片内部）：
 *   - 投影效果保留（柔和的外扩暗环，观感接近原投影）；
 *   - 磨砂透出的背景恢复均匀 → 色块消失；
 *   - 半透明磨砂 + 高光弧/暗边/菲涅尔左缘光/色散细晕/细白边全部保留。
 *
 * 视觉配方来自液态玻璃资料包 Pack-v2（glass.frag / 参数表）：
 * 细白边 edgeWidth1.5/edgeOpacity0.85、定向菲涅尔边缘光 lightAng-2.2、
 * 色散 chroma≈12（外红内蓝细晕）、磨砂白渐变（iOS 浅色玻璃配方）。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    // v10.173：卡片背后的糊化背景（纯 CPU 制作，见 BlurredBackdrop）。
    // 路线红线：不碰 AGSL / RenderEffect / graphicsLayer / 嵌套 Modifier.blur
    // （本机全部静默失效或黑层）；只用「按卡片实际位置裁剪 → 迭代减半缩样」。
    backdropSettings: AgentSettings? = null,
    bgOrigin: Offset = Offset.Zero,
    bgSize: IntSize = IntSize.Zero,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val shape = RoundedCornerShape(corner)
    var cardFrame by remember { mutableStateOf(Offset.Zero to IntSize.Zero) }

    Box(modifier.onGloballyPositioned {
        cardFrame = it.positionInWindow() to IntSize(it.size.width, it.size.height)
    }) {
        // ---- 0) 手动环形投影：只存在于卡片轮廓"外侧"，向外出 7 环递减淡出 ----
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val rings = 7
                    val stepPx = 2.5.dp.toPx()
                    for (i in 1..rings) {
                        val e = i * stepPx
                        val t = i / (rings + 1f)
                        val alpha = 0.13f * (1f - t).let { it * it * (1f + t) } // 平滑递减
                        drawRoundRect(
                            color = Color.Black.copy(alpha = alpha),
                            topLeft = Offset(-e, -e),
                            size = Size(size.width + e * 2, size.height + e * 2),
                            cornerRadius = CornerRadius(radiusPx + e, radiusPx + e),
                            style = Stroke(width = stepPx)
                        )
                    }
                }
        )

        // ---- 0a) 卡片背后的糊化背景（v10.173）：按卡片实际位置从壁纸裁出
        // 对应区域 → 迭代减半到宽约 30px（等价强磨砂，纯 CPU 毫秒级）→
        // 双线性放大铺进卡片。颜色区域与卡片背后严格对应（修掉 v10.171
        // 「整张壁纸缩影」的错误），且完全绕开本机不可靠的 GPU 效果管线。
        // 纯色背景不加（v10.157 原样）。位于磨砂白渐变之下、投影之上。
        if (backdropSettings?.bgType == "image" && bgSize != IntSize.Zero) {
            Box(Modifier.matchParentSize().clip(shape)) {
                BlurredBackdrop(
                    backdropSettings,
                    Modifier.fillMaxSize(),
                    cardPos = cardFrame.first - bgOrigin,
                    cardSize = cardFrame.second,
                    bgSize = bgSize
                )
            }
        }

        // ---- 1) 玻璃体：磨砂 + 内侧质感，全部 clip 进卡片形状 ----
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x66FFFFFF),   // 白 40%
                            Color(0x52F4F7FF),   // 32%
                            Color(0x40E3EAFF)    // 25%
                        )
                    )
                )
                .drawBehind {
                    // Pack-v2 质感要素（卡片内描边实现）：
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    val r = CornerRadius(radiusPx - stroke, radiusPx - stroke)
                    val rect = Rect(inset, inset, size.width - inset, size.height - inset)

                    // 1) 顶部内侧高光弧：玻璃上缘受光
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)
                        ),
                        topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                        style = Stroke(width = stroke)
                    )
                    // 2) 底部内侧暗边：厚度感
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.05f))
                        ),
                        topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                        style = Stroke(width = stroke)
                    )
                    // 3) 菲涅尔边缘光（定向，光源在左）
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.30f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.White.copy(alpha = 0.10f)
                            )
                        ),
                        topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                        style = Stroke(width = stroke)
                    )
                    // 4) 色散彩边：外圈红晕 + 内圈蓝晕
                    val ca = 1.5.dp.toPx()
                    drawRoundRect(
                        color = Color(0x12FF4646),
                        topLeft = Offset(rect.left - ca, rect.top - ca),
                        size = Size(rect.width + ca * 2, rect.height + ca * 2),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = Stroke(width = ca)
                    )
                    drawRoundRect(
                        color = Color(0x12465AFF),
                        topLeft = Offset(rect.left + ca, rect.top + ca),
                        size = Size(rect.width - ca * 2, rect.height - ca * 2),
                        cornerRadius = CornerRadius(radiusPx - ca * 2, radiusPx - ca * 2),
                        style = Stroke(width = ca)
                    )
                }
        )

        // ---- 2) 细白边框 ----
        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, Color.White.copy(alpha = 0.85f), shape)
        )

        // ---- 3) 内容层 ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}


/**
 * v10.173：卡片背后的糊化背景，纯 CPU 制作：
 * 1. decodeWallpaper(480) 解码小图（AppBackground.kt 的 internal 函数）；
 * 2. 换算 ContentScale.Crop 的实际可见区域（图像居中裁剪到屏幕宽高比）；
 * 3. 卡片在背景层中的比例位置（外扩 15% 余量）映射进可见区域 → 精确裁出
 *    「卡片背后那一块」；
 * 4. 降采样到 96px 宽的工作位图后跑 stackBlur(半径10)：滑动窗口均值，
 *    水平/垂直各两遍，数学上逼近高斯糊（不是缩放取巧）；96×~200 ≈ 2 万像素，
 *    纯 Kotlin 毫秒级完成；调糊度改 stackBlur 半径或 96 这个工作宽度；
 * 5. Image 以 FilterQuality.High 双线性放大 + FillBounds 铺满卡片。
 * 位置用 remember(path, 比例) 缓存，静止时不重算。
 */
@Composable
private fun BlurredBackdrop(
    s: AgentSettings,
    modifier: Modifier = Modifier,
    cardPos: Offset,
    cardSize: IntSize,
    bgSize: IntSize
) {
    val path = s.bgImagePath
    val fx = (cardPos.x / bgSize.width).coerceIn(0f, 1f)
    val fy = (cardPos.y / bgSize.height).coerceIn(0f, 1f)
    val fw = (cardSize.width / bgSize.width).coerceIn(0f, 1f)
    val fh = (cardSize.height / bgSize.height).coerceIn(0f, 1f)
    val keyFx = (fx * 200).roundToInt()
    val keyFy = (fy * 200).roundToInt()
    val keyFw = (fw * 200).roundToInt()
    val keyFh = (fh * 200).roundToInt()
    val bmp = remember(path, keyFx, keyFy, keyFw, keyFh) {
        runCatching {
            val src = decodeWallpaper(path, 480) ?: return@remember null
            val iw = src.width.toFloat(); val ih = src.height.toFloat()
            // ContentScale.Crop 的可见区域（居中）
            val imgA = iw / ih
            val scrA = bgSize.width.toFloat() / bgSize.height
            var vx0 = 0f; var vy0 = 0f; var vw = iw; var vh = ih
            if (imgA > scrA) { vw = ih * scrA; vx0 = (iw - vw) / 2f }
            else { vh = iw / scrA; vy0 = (ih - vh) / 2f }
            // 卡片区域（外扩 15%）→ 图像像素
            val m = 0.15f
            val x0 = (vx0 + (fx - m).coerceAtLeast(0f) * vw).toInt().coerceIn(0, src.width - 1)
            val y0 = (vy0 + (fy - m).coerceAtLeast(0f) * vh).toInt().coerceIn(0, src.height - 1)
            val x1 = (vx0 + (fx + fw + m).coerceAtMost(1f) * vw).toInt().coerceIn(1, src.width)
            val y1 = (vy0 + (fy + fh + m).coerceAtMost(1f) * vh).toInt().coerceIn(1, src.height)
            if (x1 - x0 < 2 || y1 - y0 < 2) return@remember null
            var b = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
            // 一次降采样到工作分辨率（96px 宽，保留色彩过渡细节），
            // 然后跑真正的盒式模糊算法（不是缩放取巧）
            if (b.width > 96) {
                val w = 96
                val h = (b.height.toFloat() * 96f / b.width).toInt().coerceAtLeast(8)
                b = Bitmap.createScaledBitmap(b, w, h, true)
            }
            b = stackBlur(b, 10)
            b
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High
        )
    }
}


/**
 * v10.173：盒式模糊（box blur）≈ 高斯糊。滑动窗口求均值，水平遍 + 垂直遍
 * 各跑两遍，窗口半径 10 → 等效模糊直径约 2×2×10+1 = 41 工作像素。
 * O(n) 滑窗实现，不按窗口大小平方增长；壁纸区域不透明（alpha 恒 255），
 * 只处理 RGB 三通道。
 */
private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
    val w = src.width
    val h = src.height
    val r = radius.coerceAtLeast(1)
    var cur = IntArray(w * h)
    src.getPixels(cur, 0, w, 0, 0, w, h)
    repeat(2) {
        cur = boxPass(cur, w, h, r, horizontal = true)
        cur = boxPass(cur, w, h, r, horizontal = false)
    }
    val out = src.copy(src.config, true)
    out.setPixels(cur, 0, w, 0, 0, w, h)
    return out
}

/** 单趟盒式模糊：horizontal=true 沿行滑窗，否则沿列滑窗。O(n)。 */
private fun boxPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
    val out = IntArray(src.size)
    val div = 2 * r + 1
    if (horizontal) {
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                out[row + x] = 0xFF000000.toInt() or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[row + (x + r + 1).coerceIn(0, w - 1)]
                val rem = src[row + (x - r).coerceIn(0, w - 1)]
                sr += (add shr 16 and 0xFF) - (rem shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (rem shr 8 and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    } else {
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = 0xFF000000.toInt() or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val rem = src[(y - r).coerceIn(0, h - 1) * w + x]
                sr += (add shr 16 and 0xFF) - (rem shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (rem shr 8 and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
    return out
}
