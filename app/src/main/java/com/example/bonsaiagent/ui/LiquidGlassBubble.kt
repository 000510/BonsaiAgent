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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.bonsaiagent.data.AgentSettings

/**
 * v10.164：液态玻璃卡片（清透版，整卡重写）。
 *
 * 为什么删掉镜像+折射 shader（v10.145~163 的整条管线）：
 *   用户设备（vivo S30 Pro mini / Android 16）上镜像层反复渲染成黑/不可见，
 *   磨砂和提亮全部叠在"黑的镜像"上 → 无论怎么调参数卡片都是炭黑色，
 *   且毫无纹理（实测 v10.161/163 截图）。盲调 8 版无果，按用户授权整卡重写。
 *
 * 清透版原理：**不复制背景，直接透出真实背景**。
 *   卡片本体 = 一层很薄的白色渐变（清玻璃 tint），背后是什么就看见什么——
 *   永远颜色正确、永远有纹理，物理上不可能发黑。
 *   玻璃质感不再靠折射，靠"边缘定义"四件套（全部保留）：
 *     0) 环形手绘投影（外侧 7 环，物理上进不了卡片，替代会内侧压暗的 Modifier.shadow）
 *     1) 细白边框 1dp 85% + 顶部内侧高光弧 + 底部暗边 + 定向菲涅尔左缘光 + 红蓝色散细晕
 *   这是 Apple「Clear」变体液态玻璃的做法：透明体 + 精致的边缘。
 *
 * tint 分档（按背景亮度）：
 *   深背景(bgLuma<0.35，夜景/黑底)：白 14/10% + RouterScreen 切白字
 *   图片背景：白 25/17%（清透，透出壁纸）
 *   普通亮背景：白 35/25%
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    // v10.164：参数保留以兼容调用点（RouterScreen 仍在传），镜像方案已删，不再使用
    backdropSettings: AgentSettings? = null,
    backdropOrigin: Offset = Offset.Zero,
    backdropSize: IntSize = IntSize.Zero,
    bgLuma: Float = 0.95f,
    frostBoost: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val shape = RoundedCornerShape(corner)

    val deepBg = bgLuma < 0.35f
    val tintTop = when {
        deepBg -> Color(0x24FFFFFF)      // 白 14%
        frostBoost -> Color(0x40FFFFFF)  // 白 25%
        else -> Color(0x59FFFFFF)        // 白 35%
    }
    val tintBot = when {
        deepBg -> Color(0x1AFFFFFF)      // 白 10%
        frostBoost -> Color(0x2BE6EDFF)  // 白 17%
        else -> Color(0x40E3EAFF)        // 白 25%
    }

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

        // ---- 1) 玻璃体：薄 tint 渐变 + 边缘质感 + 细白边 ----
        Box(Modifier.matchParentSize().clip(shape)) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(tintTop, tintBot)))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), shape)
                    .drawBehind {
                        val stroke = 2.dp.toPx()
                        val inset = stroke / 2f
                        val r = CornerRadius(radiusPx - stroke, radiusPx - stroke)
                        val rect = Rect(inset, inset, size.width - inset, size.height - inset)

                        // 顶部内侧高光弧（玻璃上缘接住光）
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)
                            ),
                            topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                            style = Stroke(width = stroke)
                        )
                        // 底部内侧暗边（厚度感）
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.05f))
                            ),
                            topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                            style = Stroke(width = stroke)
                        )
                        // 定向菲涅尔边缘光（光源在左）
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
                        // 色散彩边（外红内蓝）
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
        }

        // ---- 2) 内容层 ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}
