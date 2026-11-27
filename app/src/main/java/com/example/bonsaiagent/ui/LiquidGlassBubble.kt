package com.example.bonsaiagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
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
 * v10.168：卡内局部背板 —— 整层模糊（v10.167）改回「只糊卡片后面」：
 *   卡片内放一份真实背景的裁剪副本（bgFrame/cardOrigin 对位，已验证正确），
 *   对副本用**纯 Modifier.blur（单一 RenderEffect，本机 v10.167 实测可跑）**，
 *   不碰 AGSL/链（本机死路）。屏幕其余部分保持清晰。
 *   失败模式安全：blur 失效 → 退回 v10.164 清透形态（颜色仍正确），不会黑。
 * v10.170：图片/深背景 tint 抬高（42/36% / 45/38%）—— 深色内容经局部模糊
 *   后透出为暗块，薄 tint 压不住。
 * v10.171：局部背板改为纯 CPU 糊位图（迭代减半采样）—— 本机 RenderEffect
 *   blur 在嵌套结构中静默失效，GPU 效果管线彻底弃用。糊度恒定、任何设备可跑。
 * 历史：v10.166：镜像管线永久弃用（v10.165 与 v10.161 管线完全一致仍渲染黑层，
 * 实证该设备 RenderEffect 镜像不可用）。本版 = v10.164 清透玻璃 +
 * 深背景档改为「深色玻璃+白字」40/34%。
 * 历史：v10.165 曾恢复「镜像 + blur」两层管线（v10.161 已验证能渲染出模糊背景；
 * v10.163 的纯黑是三层特效链 boost 在该设备上把整层打黑所致，已去掉）。
 * 模糊的作用：把背后内容的明暗边界糊软（树丛/草地的锐利边界 → 柔和色块），
 * 清透玻璃不再映出"一块深色框"。折射 shader 保留边缘掰弯。
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

    // v10.166：深背景策略改为「深色玻璃 + 白字」（iOS 深色环境同款）：
    // 白 tint 提到 40/34% 把卡片抬成中灰，RouterScreen darkBg(<0.35) 切白字。
    // 不再尝试镜像模糊——该设备 RenderEffect 镜像管线复现性渲染黑层，永久弃用。
    // v10.170：局部模糊后深色内容（树丛）透出为柔和暗块，tint 太薄压不住
    // （"中间黑一块"）。图片档 30/24% → 42/36%，深档同步抬高；
    // 有模糊纹理垫底，白度提高不会退化成 v10.167 那种"灰蒙贴片"。
    val deepBg = bgLuma < 0.35f
    val tintTop = when {
        deepBg -> Color(0x73FFFFFF)      // 白 45%
        frostBoost -> Color(0x6BFFFFFF)  // 白 42%
        else -> Color(0x66FFFFFF)        // 白 40%
    }
    val tintBot = when {
        deepBg -> Color(0x61FFFFFF)      // 白 38%
        frostBoost -> Color(0x5CE6EDFF)  // 白 36%
        else -> Color(0x59FFFFFF)        // 白 35%
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

        // ---- 1) 玻璃体：局部背板（糊）+ 薄 tint 渐变 + 边缘质感 + 细白边 ----
        Box(Modifier.matchParentSize().clip(shape)) {
            // 1a) 卡内局部背板 v10.171：**纯 CPU 糊位图**（迭代减半采样）。
            // 本机红线再+1：blur（RenderEffect）只有「直接挂全屏静态 Box」能跑，
            // 放进任何嵌套/裁剪/位移结构都**静默失效**（v10.168 只糊一半、
            // v10.169/170 完全不糊）。彻底放弃 GPU 效果管线：
            // 壁纸解码 maxSide=480 → 反复减半到宽≤30px（等价强高斯糊，纯 CPU
            // 毫秒级）→ Image 双线性放大铺满卡片。零 RenderEffect、零 graphicsLayer、
            // 零裁剪对位，糊度恒定均匀，任何设备都能跑。
            if (backdropSettings != null) {
                BlurredBackdrop(backdropSettings, Modifier.fillMaxSize())
            }
            // 1b) 薄 tint 渐变 + 边缘质感
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


/**
 * v10.171：卡片内的糊化背板。纯 CPU 制作糊位图，零 GPU 效果：
 * - image：decodeWallpaper(480) → 迭代减半到宽 ≤30px（每次 createScaledBitmap
 *   filter=true 做面积平均）→ Image 以 FilterQuality.High 双线性放大铺满卡片；
 *   放大倍率大，天然就是柔和的磨砂效果，糊度只由最终宽度（30px）决定，恒定均匀。
 * - color / 默认：直接铺纯色（纯色无需模糊）。
 * 同步解码+缩放只在小图上做（480px 起步，毫秒级），remember 缓存，切图才重算。
 */
@Composable
private fun BlurredBackdrop(s: AgentSettings, modifier: Modifier = Modifier) {
    when (s.bgType) {
        "image" -> {
            val path = s.bgImagePath
            val bmp = remember(path) {
                decodeWallpaper(path, 480)?.let { src ->
                    var b = src
                    while (b.width > 30) {
                        val w = (b.width / 2).coerceAtLeast(8)
                        val h = (b.height / 2).coerceAtLeast(8)
                        b = Bitmap.createScaledBitmap(b, w, h, true)
                    }
                    b
                }
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High
                )
            } else {
                Box(modifier.fillMaxSize().background(Color(0xFFF7F8FC)))
            }
        }
        "color" -> Box(modifier.fillMaxSize().background(Color(s.bgColor)))
        else -> Box(modifier.fillMaxSize().background(Color(0xFFF7F8FC)))
    }
}
