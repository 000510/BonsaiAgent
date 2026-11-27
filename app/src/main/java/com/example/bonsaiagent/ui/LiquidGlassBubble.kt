package com.example.bonsaiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val shape = RoundedCornerShape(corner)

    Box(modifier) {
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
