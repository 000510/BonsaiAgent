package com.example.bonsaiagent.ui

import android.graphics.ColorMatrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.bonsaiagent.data.AgentSettings

/**
 * v10.158：液态玻璃卡片（真折射版）。
 *
 * 折射实现（关键：不走全屏层、不做任何测量换算）：
 *   玻璃体 Box（卡片尺寸，clip 圆角）
 *     └ 折射层 Box（matchParentSize + graphicsLayer{ renderEffect = 链(shader, blur) })
 *         └ 镜像层 Box（屏幕尺寸，靠 translation 把整块背景平移进卡片窗口）
 *             └ AppBackground（同一份背景，绘制结果与真实背景逐像素一致）
 *   shader 用 uTex 采样的是**它自己的子树**（镜像层），p 是折射层的局部坐标，
 *   uRes 就是卡片尺寸、中心就是 uRes*0.5 —— 没有测量、没有换算，不可能错位。
 *   （v10.145~150 那套「全屏层 + 测量矩形」才是错位来源，已彻底不用。）
 *
 * 质感：环形手绘投影（替代会内侧压暗的 Modifier.shadow）+ 磨砂渐变 +
 *      顶部高光弧 + 底部暗边 + 定向菲涅尔左缘光 + 红蓝色散细晕 + 细白边。
 *
 * 降级：API<31 或 shader 构造失败 → 只画磨砂（不出错、不闪）。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
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

    var cardOrigin by remember { mutableStateOf(Offset.Zero) }

    // shader 只在 API31+ 且真的需要镜像时才构造；失败返回 null 走纯磨砂
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 31) runCatching { RuntimeShader(AGSL_REFRACT) }.getOrNull() else null
    }
    val mirrorOn = Build.VERSION.SDK_INT >= 31 &&
        backdropSettings != null && backdropSize != IntSize.Zero && shader != null

    // 磨砂强度按背景亮度分档（v10.159b）：
    //   偏暗背景(luma<0.62)：白度 15/13/11%，背景清晰透出，玻璃靠边缘光+白边定义 —— iOS 深色玻璃做法。
    //     ↑ 阈值从 0.45 提到 0.62：实测湖水 luma=0.47 原本判不中深背景分支，磨砂仍是 40% → 卡片发灰。
    //   图片亮背景(frostBoost)：最强 58/50/42%，压住花哨壁纸保证文字
    //   普通亮背景：40/32/25%
    // v10.162：深背景门槛 0.62 → 0.35。0.62 会把中间调照片（如湖面 luma≈0.47）
    // 误判成深背景，磨砂掉到 15% + 黑 scrim → 卡片近不透明发黑（v10.161 实测）。
    // 中间调一律走白玻璃+深字；只有夜景/黑底这类真深色（<0.35）才走深背景档。
    val deepBg = bgLuma < 0.35f
    val frostTop = when {
        deepBg -> Color(0x38FFFFFF)      // 白 22%
        frostBoost -> Color(0x47FFFFFF)  // 28%（v10.162：58% 太厚等于白漆，湖水透不出）
        else -> Color(0x66FFFFFF)        // 40%
    }
    val frostMid = when {
        deepBg -> Color(0x30F0F4FF)      // 19%
        frostBoost -> Color(0x3DF4F7FF)  // 24%
        else -> Color(0x52F4F7FF)        // 32%
    }
    val frostBot = when {
        deepBg -> Color(0x29E4EBFF)      // 16%
        frostBoost -> Color(0x33E3EAFF)  // 20%
        else -> Color(0x40E3EAFF)        // 25%
    }

    Box(modifier.onGloballyPositioned { cardOrigin = it.positionInWindow() }) {
        // ---- 0) 手绘环形投影：只在卡片轮廓外侧，物理上进不了内部 ----
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val rings = 7
                    val stepPx = 2.5.dp.toPx()
                    for (i in 1..rings) {
                        val e = i * stepPx
                        val t = i / (rings + 1f)
                        val alpha = 0.13f * (1f - t).let { it * it * (1f + t) }
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

        // ---- 1) 玻璃体 ----
        Box(Modifier.matchParentSize().clip(shape)) {
            // 1a) 背景镜像 + 真折射
            if (mirrorOn) {
                val dpx = with(density) { backdropSize.width.toDp() }
                val dpy = with(density) { backdropSize.height.toDp() }
                Box(
                    Modifier
                        .matchParentSize()
                        // graphicsLayer 在外：先把子树内容合成进卡片大小的 layer，再对 layer 应用 blur+折射
                        .graphicsLayer {
                            val sh = shader ?: return@graphicsLayer
                            val scale = density.density.coerceAtMost(2f)
                            sh.setFloatUniform("uRes", size.width, size.height)
                            sh.setFloatUniform("uRadius", radiusPx)
                            // (strength 折射, chroma 色散, blur 模糊px, band 折射带宽px)，全部 ×dpr
                            sh.setFloatUniform("uP", 52f * scale, 10f * scale, 1.0f * scale, 56f * scale)
                            val refractFx = RenderEffect.createRuntimeShaderEffect(sh, "uTex")
                            // v10.159：blur 24dp→10dp。24dp(48px) 把背景糊成纯色（实测行内 std≈0），
                            // 玻璃看起来就是一块灰卡；10dp(20px) 还能看出背景轮廓与明暗，才像玻璃。
                            val blurFx = RenderEffect.createBlurEffect(10f * scale, 10f * scale, Shader.TileMode.CLAMP)
                            // v10.163：iOS 液态玻璃的自适应提亮（Apple 材质的关键拼图）。
                            // 玻璃的颜色 = 背后内容的颜色；卡片后面恰好是照片的暗区时
                            // （如这张湖景的树丛），再薄的磨砂也会显得黑。
                            // 苹果的做法是按背景亮度自适应提亮：暗区把透进来的光拉亮，
                            // 玻璃在任何壁纸区域都保持「透亮」。提亮系数 1.15~1.45，
                            // bgLuma<0.5 时取 1.45（本张湖景树丛 luma≈0.4 命中此档）。
                            val boost = if (bgLuma < 0.5f) 1.45f else 1.15f
                            val boostFx = RenderEffect.createColorMatrixEffect(
                                // ⚠️ createColorMatrixEffect 的参数类型是 android.graphics.ColorMatrix，
                                // 不是 FloatArray（GLM 原包直接传 floatArrayOf 会编译失败）
                                ColorMatrix(
                                    floatArrayOf(
                                        boost, 0f, 0f, 0f, 0f,
                                        0f, boost, 0f, 0f, 0f,
                                        0f, 0f, boost, 0f, 0f,
                                        0f, 0f, 0f, 1f, 0f
                                    )
                                )
                            )
                            // 管线：镜像 → 模糊 → 提亮 → 折射（createChainEffect 外层后应用）
                            renderEffect = RenderEffect
                                .createChainEffect(refractFx, RenderEffect.createChainEffect(boostFx, blurFx))
                                .asComposeRenderEffect()
                        }
                        // clipToBounds 在内：镜像按屏幕尺寸布局，超出卡片的部分先裁掉，blur 只算卡片面积
                        .clipToBounds()
                ) {
                    Box(
                        Modifier
                            .size(dpx, dpy)
                            .graphicsLayer {
                                translationX = backdropOrigin.x - cardOrigin.x
                                translationY = backdropOrigin.y - cardOrigin.y
                            }
                    ) {
                        // 镜像只用于玻璃内部（还要 blur），用小尺寸解码即可：
                        // 避免和主背景各持一份 1440px 大图导致 OOM
                        AppBackground(backdropSettings!!, Modifier.fillMaxSize(), maxSide = 560)
                    }
                }
            }

            // 1b) 磨砂 + 内侧质感
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(frostTop, frostMid, frostBot)))
                    .drawBehind {
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
 * 边缘折射着色器（移植自 LiquidGlass-Pack-v2 / glass.frag，画心人，MIT）。
 * 与 v10.157 之前的版本不同：这里采样的是**卡内镜像层**，uRes = 卡片尺寸，
 * 中心就是 uRes*0.5 —— 不需要任何外部坐标。
 * ⚠️ AGSL(SkSL) 只有 float2/float3/float4，没有 vec2/vec3/vec4。
 */
private const val AGSL_REFRACT = """
uniform shader uTex;
uniform float2 uRes;
uniform float uRadius;
uniform float4 uP;   // (strength 折射, chroma 色散, blur 模糊px, band 折射带宽px)

float sdRB(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

// RGB 分通道采样 + 色散偏移（颜色全部来自背景镜像）
float3 tap(float2 px, float2 ca) {
    float2 hi = uRes - float2(1.0);
    float2 u = clamp(px + ca, float2(0.0), hi);
    float2 v = clamp(px,      float2(0.0), hi);
    float2 w = clamp(px - ca, float2(0.0), hi);
    return float3(uTex.eval(u).r, uTex.eval(v).g, uTex.eval(w).b);
}

float4 main(float2 p) {
    float2 c  = uRes * 0.5;
    float2 hb = uRes * 0.5;
    float d = sdRB(p - c, hb, uRadius);
    if (d > 2.0) return uTex.eval(p);

    // 朝外法线（中心差分，e=1.5，与原作一致）
    float e = 1.5;
    float2 g = float2(
        sdRB(p + float2(e, 0.0) - c, hb, uRadius) - sdRB(p - float2(e, 0.0) - c, hb, uRadius),
        sdRB(p + float2(0.0, e) - c, hb, uRadius) - sdRB(p - float2(0.0, e) - c, hb, uRadius)
    );
    g = normalize(g + 0.000001);

    // 折射衰减：t = -d/band，k = (1 - t^0.3)^2（原作第 19 行）
    float t = clamp(-d / max(uP.w, 1.0), 0.0, 1.0);
    float k = pow(1.0 - pow(t, 0.30), 2.0);

    float2 refr = -g * k * uP.x;
    float2 ca   = -g * k * uP.y;
    float bs = uP.z;

    // 5 点十字模糊
    float3 s = tap(p + refr, ca)
             + tap(p + refr + float2(bs, 0.0), ca)
             + tap(p + refr - float2(bs, 0.0), ca)
             + tap(p + refr + float2(0.0, bs), ca)
             + tap(p + refr - float2(0.0, bs), ca);
    s /= 5.0;

    return float4(s, 1.0);
}
"""
