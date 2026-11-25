package com.example.bonsaiagent.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
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
 * v10.165：恢复「镜像 + blur」两层管线（v10.161 已验证能渲染出模糊背景；
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

    val deepBg = bgLuma < 0.35f
    val tintTop = when {
        deepBg -> Color(0x24FFFFFF)      // 白 14%
        frostBoost -> Color(0x52FFFFFF)  // 白 32%
        else -> Color(0x59FFFFFF)        // 白 35%
    }
    val tintBot = when {
        deepBg -> Color(0x1AFFFFFF)      // 白 10%
        frostBoost -> Color(0x45E6EDFF)  // 白 27%
        else -> Color(0x40E3EAFF)        // 白 25%
    }

    var cardOrigin by remember { mutableStateOf(Offset.Zero) }

    // shader 只在 API31+ 且真的需要镜像时才构造；失败返回 null 走纯磨砂
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 31) runCatching { RuntimeShader(AGSL_REFRACT) }.getOrNull() else null
    }
    val mirrorOn = Build.VERSION.SDK_INT >= 31 &&
        backdropSettings != null && backdropSize != IntSize.Zero && shader != null

    Box(modifier.onGloballyPositioned { cardOrigin = it.positionInWindow() }) {
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

        // ---- 1) 玻璃体：镜像模糊层 + 薄 tint 渐变 + 边缘质感 + 细白边 ----
        Box(Modifier.matchParentSize().clip(shape)) {
            // 1a) 背景镜像 + blur（两层特效链：shader 折射 + blur；不再叠 boost）
            if (mirrorOn) {
                val dpx = with(density) { backdropSize.width.toDp() }
                val dpy = with(density) { backdropSize.height.toDp() }
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val sh = shader ?: return@graphicsLayer
                            val scale = density.density.coerceAtMost(2f)
                            sh.setFloatUniform("uRes", size.width, size.height)
                            sh.setFloatUniform("uRadius", radiusPx)
                            sh.setFloatUniform("uP", 52f * scale, 10f * scale, 1.0f * scale, 56f * scale)
                            val refractFx = RenderEffect.createRuntimeShaderEffect(sh, "uTex")
                            val blurFx = RenderEffect.createBlurEffect(10f * scale, 10f * scale, Shader.TileMode.CLAMP)
                            // 先模糊（inner）再折射（outer）—— 只两层，与 v10.161 一致
                            renderEffect = RenderEffect
                                .createChainEffect(refractFx, blurFx)
                                .asComposeRenderEffect()
                        }
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
                        // 镜像用小尺寸解码（maxSide=560）防 OOM
                        AppBackground(backdropSettings!!, Modifier.fillMaxSize(), maxSide = 560)
                    }
                }
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
 * 边缘折射着色器（移植自 LiquidGlass-Pack-v2 / glass.frag，画心人，MIT）。
 * 采样的是卡内镜像层，uRes = 卡片尺寸，中心就是 uRes*0.5 —— 无外部坐标。
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

    float e = 1.5;
    float2 g = float2(
        sdRB(p + float2(e, 0.0) - c, hb, uRadius) - sdRB(p - float2(e, 0.0) - c, hb, uRadius),
        sdRB(p + float2(0.0, e) - c, hb, uRadius) - sdRB(p - float2(0.0, e) - c, hb, uRadius)
    );
    g = normalize(g + 0.000001);

    float t = clamp(-d / max(uP.w, 1.0), 0.0, 1.0);
    float k = pow(1.0 - pow(t, 0.30), 2.0);

    float2 refr = -g * k * uP.x;
    float2 ca   = -g * k * uP.y;
    float bs = uP.z;

    float3 s = tap(p + refr, ca)
             + tap(p + refr + float2(bs, 0.0), ca)
             + tap(p + refr - float2(bs, 0.0), ca)
             + tap(p + refr + float2(0.0, bs), ca)
             + tap(p + refr - float2(0.0, bs), ca);
    s /= 5.0;

    return float4(s, 1.0);
}
"""
