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
import androidx.compose.ui.draw.shadow
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
 * v10.151：液态玻璃彻底重写。
 *
 * 旧架构（v10.145~v10.150）：AGSL shader 挂在全屏折射层上，按测量的卡片矩形画玻璃。
 * 四个版本实测都有一条与卡片错位的矩形色带（左右边缘恒定 x≈118/1097、顶部贴标题行
 * 底部、收缩/展开都在），颜色始终等于玻璃色 —— 说明玻璃被按某个错误矩形多画了一层，
 * 且这条测量/合成链路在本机上有无法从代码侧定位的歧义。按用户决定整条删掉重写。
 *
 * 新架构：**玻璃 100% 画在卡片内部** —— clip 到卡片形状，物理上不可能与卡片错位：
 *   磨砂渐变 + 顶部内侧高光 + 底部内侧暗边 + 细白边 + 投影。
 * 视觉对应 iOS 浅色玻璃配方（blur + 白渐变 + 内高光 + 细边 + 阴影）。
 * 代价是放弃了全屏边缘折射（纯色背景上本来也几乎看不出来；图片背景下想要
 * 完整折射效果可后续以「玻璃后面内容复制进卡片」的方式再实现，不再走全屏层）。
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
        // ---- 投影：中性黑，画在卡片外沿 ----
        Box(
            Modifier
                .matchParentSize()
                .shadow(
                    elevation = 14.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x33000000),
                    spotColor = Color(0x3D000000)
                )
        )

        // ---- 玻璃体：全部 clip 进卡片形状，任何设备上都不可能错位 ----
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .drawBehind {
                    // 以下全部对应 Pack-v2 / glass.frag 的质感要素（参数表默认值，×min(dpr,2)），
                    // 只是改为「卡片内部描边」的实现方式，不再依赖全屏折射层：
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    val r = CornerRadius(radiusPx - stroke, radiusPx - stroke)
                    val rect = Rect(inset, inset, size.width - inset, size.height - inset)

                    // 1) 顶部内侧高光弧：玻璃上缘的受光
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)
                        ),
                        topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                        style = Stroke(width = stroke)
                    )
                    // 2) 底部内侧暗边：玻璃厚度感
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.05f))
                        ),
                        topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                        style = Stroke(width = stroke)
                    )
                    // 3) 菲涅尔边缘光（定向）：原作 lightAng=-2.2rad、light=0.35 —— 光源在左，
                    //    左缘亮、右缘留一点次级反光。用横向渐变描边近似。
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
                    // 4) 色散彩边（原作 chroma=12 的近似）：外侧一圈红晕 + 内一圈蓝晕，各 1.5px
                    val ca = 1.5.dp.toPx()
                    drawRoundRect(
                        color = Color(0x12FF4646), // 红晕
                        topLeft = Offset(rect.left - ca, rect.top - ca),
                        size = Size(rect.width + ca * 2, rect.height + ca * 2),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = Stroke(width = ca)
                    )
                    drawRoundRect(
                        color = Color(0x12465AFF), // 蓝晕
                        topLeft = Offset(rect.left + ca, rect.top + ca),
                        size = Size(rect.width - ca * 2, rect.height - ca * 2),
                        cornerRadius = CornerRadius(radiusPx - ca * 2, radiusPx - ca * 2),
                        style = Stroke(width = ca)
                    )
                }
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x66FFFFFF),   // 白 40%（对应 iOS 白渐变 .22 起，纯色底略加强）
                            Color(0x52F4F7FF),   // 32%
                            Color(0x40E3EAFF)    // 25%
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.85f), shape) // 原作 edgeW1.5 / edgeOp0.85
        )

        // ---- 内容层 ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}
