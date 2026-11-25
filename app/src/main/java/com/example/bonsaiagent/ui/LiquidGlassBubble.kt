package com.example.bonsaiagent.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * v10.155 诊断版：磨砂 = 纯白 @ 可调透明度（诊断面板滑条控制）。
 * 平坦单色，排除渐变/染色干扰，专心定位色块来源。
 * 诊断完成后恢复正式版：磨砂换回垂直渐变，磨砂层叠加步骤见 v10.151 说明。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    frostAlpha: Float = 0.30f,
    showShadow: Boolean = true,
    showStrokes: Boolean = true,
    showBorder: Boolean = true,
    backdrop: (@Composable BoxScope.() -> Unit)? = null,
    backdropOrigin: Offset = Offset.Zero,
    backdropSize: IntSize = IntSize.Zero,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val shape = RoundedCornerShape(corner)
    val blurOn = Build.VERSION.SDK_INT >= 31 && backdrop != null && backdropSize != IntSize.Zero
    var cardOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(modifier.onGloballyPositioned { cardOrigin = it.positionInWindow() }) {
        // ---- 0) 投影：中性黑 ----
        if (showShadow) Box(
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

        // ---- 1) 玻璃体 ----
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
        ) {
            // 1a) 背景镜像（可选）：全屏背景平移对齐后整体模糊 ——
            //     磨砂透出的是"糊掉的背景"，玻璃感的物理前提；开启后磨砂
            //     合成的是卡内镜像而非背后内容，色块无从透出。
            if (blurOn) {
                val dpx = with(density) { backdropSize.width.toDp() }
                val dpy = with(density) { backdropSize.height.toDp() }
                Box(
                    Modifier
                        .matchParentSize()
                        .blur(22.dp)
                ) {
                    Box(
                        Modifier
                            .size(dpx, dpy)
                            .graphicsLayer {
                                translationX = backdropOrigin.x - cardOrigin.x
                                translationY = backdropOrigin.y - cardOrigin.y
                            }
                    ) {
                        backdrop?.invoke(this)
                    }
                }
            }

            // 1b) 磨砂层：纯白 @ frostAlpha（诊断可调）
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = frostAlpha))
                    .then(if (showStrokes) Modifier.drawBehind {
                        // Pack-v2 质感要素（卡片内描边实现）：
                        val stroke = 2.dp.toPx()
                        val inset = stroke / 2f
                        val r = CornerRadius(radiusPx - stroke, radiusPx - stroke)
                        val rect = Rect(inset, inset, size.width - inset, size.height - inset)

                        // 顶部内侧高光弧
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)
                            ),
                            topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                            style = Stroke(width = stroke)
                        )
                        // 底部内侧暗边
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.05f))
                            ),
                            topLeft = rect.topLeft, size = rect.size, cornerRadius = r,
                            style = Stroke(width = stroke)
                        )
                        // 菲涅尔边缘光（定向，光源在左）
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
                        // 色散彩边（外红内蓝，各 1.5px）
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
                    } else Modifier)
            )
        }

        // ---- 2) 细白边框 ----
        if (showBorder) Box(
            Modifier
                .matchParentSize()
                .border(1.dp, Color.White.copy(alpha = 0.85f), shape)
        )

        // ---- 3) 内容层 ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}

