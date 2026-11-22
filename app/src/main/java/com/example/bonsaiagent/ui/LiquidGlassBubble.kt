package com.example.bonsaiagent.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/**
 * v10.137：真折射液态玻璃卡片（照「画心人 LiquidGlass-Pack v2」的 WebGL shader 移植）。
 *
 * 做法：
 *  - 组件自己画一层柔和渐变当「背景纹理」，用 Android 13+ 的 AGSL RuntimeShader 对它做
 *    边缘折射 + RGB 色散 + 5 点约 1px 模糊 + 细白边 + 边缘光（真折射，不是 CSS 磨砂）。
 *  - 关键点：折射采样的是「本 layer 自己画的渐变」，所以不需要截屏/后台模糊权限，
 *    纯 Compose 渲染，稳定可控。玻璃矩形比 layer 略内缩（pad），给边缘折射留出外侧背景。
 *  - Android 12 及以下 / shader 创建失败：自动回退到「半透明白 + 白边 + 圆角」毛玻璃，不会黑屏。
 *
 * 复用方式：LiquidGlassCard(modifier, gradient){ 内容 } —— 内容会被 pad 内缩对齐玻璃边界。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    gradient: List<Color> = listOf(Color(0xFFEAF2FF), Color(0xFFF1EAFF), Color(0xFFE6F7FF)),
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    // 只在 Android 13+ 建 shader；任何异常都回退（AGSL 是运行时编译，出错不能让它把界面带崩）
    val shader: RuntimeShader? = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                RuntimeShader(AGSL_LIQUID_GLASS)
            } catch (t: Throwable) {
                null
            }
        } else null
    }

    val radiusPx = with(density) { corner.toPx() }
    val padPx = with(density) { pad.toPx() }
    val shape = RoundedCornerShape(corner)

    Box(modifier) {
        // ---- 背景（折射纹理）层 ----
        if (shader != null) {
            val s = shader
            Box(
                Modifier
                    .matchParentSize()
                    .onGloballyPositioned { sizePx = it.size }
                    .graphicsLayer {
                        val w = sizePx.width.toFloat()
                        val h = sizePx.height.toFloat()
                        if (w > 0f && h > 0f) {
                            val scale = density.density.coerceAtMost(2f)
                            // 所有像素参数 × min(dpr,2)，跟原 shader 一致
                            s.setFloatUniform("uRes", w, h)
                            s.setFloatUniform("uCenter", w / 2f, h / 2f)
                            s.setFloatUniform("uHalf", w / 2f - padPx, h / 2f - padPx)
                            s.setFloatUniform("uRadius", radiusPx)
                            s.setFloatUniform("uStrength", 40f * scale)   // 折射
                            s.setFloatUniform("uChroma", 11f * scale)     // 色散
                            s.setFloatUniform("uBlur", 1.2f * scale)      // 约 1px 模糊
                            s.setFloatUniform("uBand", 70f * scale)       // 折射带宽
                            s.setFloatUniform("uEdgeW", 1.6f * scale)     // 白边
                            s.setFloatUniform("uEdgeOp", 0.9f)            // 白边不透明度
                            s.setFloatUniform("uLightAng", (PI * 0.78).toFloat()) // 光源角度
                            s.setFloatUniform("uLight", 1.1f)             // 边缘光强度
                            renderEffect = RenderEffect
                                .createRuntimeShaderEffect(s, "uTex")
                                .asComposeRenderEffect()
                        }
                    }
                    // layer 内容 = 渐变（被 shader 当 uTex 折射）
                    .background(Brush.linearGradient(gradient))
            )
        } else {
            // 回退：普通毛玻璃（半透明白渐变 + 细白边 + 圆角）
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(Color(0xF2FFFFFF), Color(0xC4EAF2FF))))
                    .border(1.dp, Color.White.copy(alpha = 0.78f), shape)
            )
        }

        // ---- 内容层（文字/按钮），内缩 pad 对齐玻璃边界 ----
        Box(Modifier.matchParentSize().padding(pad), content = content)
    }
}

/**
 * AGSL 版液态玻璃着色器（移植自 glass.frag，简化为单圆角矩形）。
 * 与 WebGL 版的差异：uniform sampler2D → uniform shader(uTex.eval)；uv 归一化 → 直接用像素坐标。
 */
private const val AGSL_LIQUID_GLASS = """
uniform shader uTex;
uniform float2 uRes;
uniform float2 uCenter;
uniform float2 uHalf;
uniform float uRadius;
uniform float uStrength;
uniform float uChroma;
uniform float uBlur;
uniform float uBand;
uniform float uEdgeW;
uniform float uEdgeOp;
uniform float uLightAng;
uniform float uLight;

float sdRB(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

float sdScene(float2 p) {
    return sdRB(p - uCenter, uHalf, uRadius);
}

float3 tapx(float2 px, float2 ca) {
    float2 a = clamp(px + ca, float2(0.0), uRes);
    float2 c = clamp(px, float2(0.0), uRes);
    float2 e = clamp(px - ca, float2(0.0), uRes);
    return float3(uTex.eval(a).r, uTex.eval(c).g, uTex.eval(e).b);
}

float4 main(float2 p) {
    float d = sdScene(p);
    if (d > 3.0) {
        return float4(0.0);
    }
    float e = 1.5;
    float2 g = normalize(float2(
        sdScene(p + float2(e, 0.0)) - sdScene(p - float2(e, 0.0)),
        sdScene(p + float2(0.0, e)) - sdScene(p - float2(0.0, e))
    ) + float2(0.000001));
    float t = clamp(-d / max(uBand, 1.0), 0.0, 1.0);
    float k = pow(1.0 - pow(t, 0.30), 2.0);
    float2 refr = -g * k * uStrength;
    float2 ca = -g * k * uChroma;
    float2 bp = p + refr;
    float bs = uBlur;
    float3 s = tapx(bp, ca)
        + tapx(bp + float2(bs, 0.0), ca) + tapx(bp - float2(bs, 0.0), ca)
        + tapx(bp + float2(0.0, bs), ca) + tapx(bp - float2(0.0, bs), ca);
    s = s / 5.0;
    float3 col = s * 1.06 + float3(0.015, 0.025, 0.048);
    col = col + smoothstep(uHalf.y * 0.35, -uHalf.y * 1.1, p.y - uCenter.y) * float3(0.055, 0.075, 0.105);
    float2 Ld = float2(cos(uLightAng), sin(uLightAng));
    float rim = 1.0 - smoothstep(0.0, 7.0, -d);
    col = col + uLight * rim * pow(max(dot(g, Ld), 0.0), 2.0);
    col = col + uLight * 0.45 * rim * pow(max(dot(g, -Ld), 0.0), 3.0);
    float line = 1.0 - smoothstep(0.0, uEdgeW, abs(d));
    col = mix(col, float3(1.0), line * uEdgeOp);
    float inside = 1.0 - smoothstep(-0.8, 0.8, d);
    float alpha = max(inside, line * uEdgeOp);
    return float4(col, alpha);
}
"""
