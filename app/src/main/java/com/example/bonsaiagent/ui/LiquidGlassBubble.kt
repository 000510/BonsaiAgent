package com.example.bonsaiagent.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
 * v10.138：真折射液态玻璃卡片（照「画心人 LiquidGlass-Pack v2」的 WebGL shader 移植）。
 *
 * v10.137 的教训（用户真机反馈「纯白的，没有任何效果」）：
 *  - 玻璃里画的是 #EAF2FF/#F1EAFF 这种接近白的淡色，shader 里又 ×1.06 提亮 + 加偏移，
 *    白底白卡 → 全糊成一片白，完全看不出玻璃。液态玻璃必须有「可被折射的彩色内容」才显形。
 *  - 现在：玻璃自带虹彩渐变（蓝→紫→青，有真实色阶）+ 左上斜向反光带 + 顶部/边缘白色镜面高光
 *    + 彩色投影（浮起感），shader 只做折射/色散/厚边，不再提亮。
 *
 * 做法：AGSL RuntimeShader 对「本层自己画的渐变」做边缘折射 + RGB 色散 + 5 点约 1px 模糊
 * + 玻璃厚边柔亮 + 细白边，纯 Compose 渲染，不截屏、不要后台模糊权限。
 * Android 12 及以下 / shader 失败 → 回退成同配色的毛玻璃，不会黑屏也不会变白板。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    gradient: List<Color> = IRIS_GLASS,
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
        // ---- 0) 阴影层：彩色投影（API28+ 是蓝紫光晕，低版本自动退成黑阴影）----
        Box(
            Modifier
                .matchParentSize()
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x40607BFF),
                    spotColor = Color(0x557A5BFF)
                )
        )

        // ---- 1) 玻璃层（折射纹理）----
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
                            s.setFloatUniform("uStrength", 30f * scale)   // 折射强度
                            s.setFloatUniform("uChroma", 14f * scale)     // RGB 色散
                            s.setFloatUniform("uBlur", 1.0f * scale)      // 约 1px 模糊
                            s.setFloatUniform("uBand", 80f * scale)       // 折射带宽
                            s.setFloatUniform("uEdgeW", 1.4f * scale)     // 细白边
                            s.setFloatUniform("uEdgeOp", 0.92f)           // 白边不透明度
                            s.setFloatUniform("uLightAng", (PI * 0.78).toFloat())
                            s.setFloatUniform("uLight", 0.5f)             // 边缘镜面高光
                            renderEffect = RenderEffect
                                .createRuntimeShaderEffect(s, "uTex")
                                .asComposeRenderEffect()
                        }
                    }
                    // layer 内容 = 虹彩渐变 + 左上斜向反光（都会被 shader 当 uTex 折射）
                    .background(irisBrush(gradient))
                    .background(reflectBrush())
            )
        } else {
            // 回退：同配色毛玻璃（半透明虹彩 + 白边 + 圆角），确保不是白板
            Box(
                Modifier
                    .matchParentSize()
                    .padding(pad)
                    .clip(shape)
                    .background(irisBrush(gradient))
                    .background(reflectBrush())
                    .border(1.2.dp, Color.White.copy(alpha = 0.85f), shape)
            )
        }

        // ---- 2) 玻璃边缘描边：上亮下暗，模拟玻璃断面 ----
        Box(
            Modifier
                .matchParentSize()
                .padding(pad)
                .clip(shape)
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFFFFFFFF),
                            Color(0xCCFFFFFF),
                            Color(0x8899BBFF),
                            Color(0xE6FFFFFF)
                        )
                    ),
                    shape = shape
                )
        )

        // ---- 3) 内容层：用自身尺寸撑开父 Box（不能 matchParentSize，否则父高度塌成 0 不可见）----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}

/** 虹彩渐变底色：白 → 淡蓝 → 淡紫 → 淡青，有真实色阶，白底上能看出玻璃 */
private val IRIS_GLASS = listOf(
    Color(0xFFFFFFFF),
    Color(0xFFD3E2FF),
    Color(0xFFE0D0FF),
    Color(0xFFC9EDFF)
)

private fun irisBrush(colors: List<Color>): Brush =
    Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to (colors.getOrNull(0) ?: Color.White),
            0.32f to (colors.getOrNull(1) ?: Color(0xFFD3E2FF)),
            0.62f to (colors.getOrNull(2) ?: Color(0xFFE0D0FF)),
            1.00f to (colors.getOrNull(3) ?: Color(0xFFC9EDFF))
        )
    )

/** 左上斜向反光带（玻璃那层白白的光泽），叠在虹彩上 */
private fun reflectBrush(): Brush =
    Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to Color(0xB3FFFFFF),
            0.18f to Color(0x4DFFFFFF),
            0.42f to Color(0x00FFFFFF),
            1.00f to Color(0x00FFFFFF)
        )
    )

/**
 * AGSL 版液态玻璃着色器（移植自 glass.frag，简化为单圆角矩形）。
 * 与 WebGL 版的差异：uniform sampler2D → uniform shader(uTex.eval)；uv 归一化 → 直接用像素坐标。
 * 与 v10.137 的差异：不再 ×1.06 提亮（那会把淡渐变洗成纯白），改为按深度轻微明暗 + 玻璃厚边柔亮。
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

float3 tap3(float2 px, float2 ca) {
    float2 a = clamp(px + ca, float2(0.0), uRes - float2(1.0));
    float2 c = clamp(px, float2(0.0), uRes - float2(1.0));
    float2 e = clamp(px - ca, float2(0.0), uRes - float2(1.0));
    return float3(uTex.eval(a).r, uTex.eval(c).g, uTex.eval(e).b);
}

float4 main(float2 p) {
    float d = sdScene(p);
    if (d > 3.5) return float4(0.0);

    float e = 1.2;
    float2 g = float2(
        sdScene(p + float2(e, 0.0)) - sdScene(p - float2(e, 0.0)),
        sdScene(p + float2(0.0, e)) - sdScene(p - float2(0.0, e))
    );
    g = g / max(length(g), 0.00001);

    float depth = clamp(-d / max(uBand, 1.0), 0.0, 1.0);
    float k = pow(1.0 - pow(depth, 0.35), 2.0);

    float2 refr = -g * k * uStrength;
    float2 ca = -g * k * uChroma;
    float2 bp = p + refr;
    float bs = uBlur;

    float3 s = tap3(bp, ca)
        + tap3(bp + float2(bs, 0.0), ca)
        + tap3(bp - float2(bs, 0.0), ca)
        + tap3(bp + float2(0.0, bs), ca)
        + tap3(bp - float2(0.0, bs), ca);
    s = s * 0.2;

    float3 col = s;
    col = col * mix(0.985, 1.015, depth);

    float thick = 1.0 - smoothstep(0.0, 16.0, -d);
    col = col + float3(0.11, 0.12, 0.14) * thick * thick * 0.5;

    float2 Ld = float2(cos(uLightAng), sin(uLightAng));
    float dir = max(dot(g, Ld), 0.0);
    float rimW = 1.0 - smoothstep(0.0, 7.0, -d);
    col = col + uLight * rimW * (0.30 + 0.70 * dir * dir);

    float line = 1.0 - smoothstep(0.0, max(uEdgeW, 0.5), abs(d));
    col = mix(col, float3(1.0), line * uEdgeOp);

    float inside = 1.0 - smoothstep(-0.75, 0.75, d);
    float alpha = max(inside, line * uEdgeOp);
    return float4(clamp(col, 0.0, 1.0), alpha);
}
"""
