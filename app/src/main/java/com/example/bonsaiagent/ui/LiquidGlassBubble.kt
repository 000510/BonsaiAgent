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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/**
 * v10.139：真折射液态玻璃 —— 严格照「画心人 LiquidGlass-Pack v2」demo 的做法移植。
 *
 * 三代踩坑记录：
 *  - v10.137：玻璃里画接近白的淡色 + shader 提亮 → 纯白，什么都看不见。
 *  - v10.138：玻璃里画虹彩渐变 → 有颜色了，但「没有那个效果」。
 *  - 用户给了资料包，翻作者的 demo 源码才看清关键：demo 的 backdrop 根本不是网页内容，
 *    而是它自己画的一张「背景图」——
 *        线性渐变 #e3edfc → #b4d0f7(0.4) → #79abf0(0.75) → #f2c4e0
 *      + 4 个 blur(40px) 的椭圆光斑（白 / 白 / 蓝 #4f8cf0 / 粉 #ff9ec9）
 *      + 几道 rgba(255,255,255,.4) 的淡白横线 + 一个白色大字
 *    shader 折的就是这张图。**「透明玻璃感」的直接来源是那些淡白横线被掰弯**
 *    （大块柔和渐变位移了也看不出来，有清晰边界的线一弯就一眼看出是玻璃）。
 *
 * 所以这一版：
 *  1) 在气泡背后的消息层里，按 demo 原参数画出同款背景（渐变 + 4 光斑 + 淡白横线）；
 *  2) 用 AGSL 只在该气泡圆角矩形内做：边缘折射(位移) + RGB 色散 + 5 点约 1px 模糊
 *     + 玻璃厚度柔亮 + 顶部镜面高光 + 1.5px 细白边；矩形外原样返回，其余界面零影响；
 *  3) 参数照 demo 默认：strength 48 / chroma 12 / blur 1.2 / edgeW 1.5 / edgeOp 0.85，
 *     全部 × min(dpr,2)；
 *  4) 气泡自己只画「框」（断面描边 + 厚度柔光 + 彩色投影），不再自己上色。
 *  Android 12 及以下无 AGSL → 退回自绘虹彩玻璃，不会白板也不会崩。
 */
@Composable
fun Modifier.liquidRefractBackground(
    enabled: Boolean,
    bubble: Rect,
    corner: Dp = 26.dp
): Modifier {
    // 无条件 remember（避免条件性创建导致槽位抖动）
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(AGSL_BG_REFRACT) }.getOrNull()
        } else null
    }
    if (!enabled || shader == null || bubble.width <= 1f || bubble.height <= 1f) return this

    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val lineStep = with(density) { 52.dp.toPx() }
    val lineW = with(density) { 1.4.dp.toPx() }

    return this
        // 背景层内容 = 原界面 + 气泡区域内「demo 同款背景」（折射参照物）
        .drawWithContent {
            drawContent()
            if (bubble.width > 1f && bubble.height > 1f) {
                val path = Path().apply {
                    addRoundRect(RoundRect(bubble, CornerRadius(radiusPx, radiusPx)))
                }
                clipPath(path) {
                    // ① demo 线性渐变
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.00f to Color(0xFFE3EDFC),
                                0.40f to Color(0xFFB4D0F7),
                                0.75f to Color(0xFF79ABF0),
                                1.00f to Color(0xFFF2C4E0)
                            ),
                            start = bubble.topLeft,
                            end = bubble.bottomRight
                        ),
                        topLeft = bubble.topLeft,
                        size = bubble.size
                    )

                    // ② demo 的 4 个 blur(40px) 椭圆光斑 —— 用径向渐变模拟
                    val w = bubble.width
                    val h = bubble.height
                    blob(bubble.left + w * 0.15f, bubble.top + h * 0.20f,
                        Color(0xC0FFFFFF), w * 0.62f)
                    blob(bubble.left + w * 0.80f, bubble.top + h * 0.75f,
                        Color(0x99FFFFFF), w * 0.62f)
                    blob(bubble.left + w * 0.55f, bubble.top + h * 0.50f,
                        Color(0xAA4F8CF0), w * 0.75f)
                    blob(bubble.left + w * 0.20f, bubble.top + h * 0.85f,
                        Color(0xAAFF9EC9), w * 0.62f)

                    // ③ demo 的淡白横线 —— 折射的「参照物」，被掰弯才看得出玻璃
                    var y = bubble.top + lineStep * 0.35f
                    while (y < bubble.bottom) {
                        drawRect(
                            color = Color(0x59FFFFFF),
                            topLeft = Offset(bubble.left, y),
                            size = Size(bubble.width, lineW)
                        )
                        y += lineStep
                    }
                }
            }
        }
        .graphicsLayer {
            val scale = density.density.coerceAtMost(2f)
            shader.setFloatUniform("uRes", size.width, size.height)
            shader.setFloatUniform("uCenter", bubble.center.x, bubble.center.y)
            shader.setFloatUniform("uHalf", bubble.width / 2f, bubble.height / 2f)
            shader.setFloatUniform("uRadius", radiusPx)
            // demo 默认 strength 48 / chroma 12 / blur 1.2 / band 60 / edgeW 1.5 / edgeOp .85
            shader.setFloatUniform("uStrength", 48f * scale)
            shader.setFloatUniform("uChroma", 12f * scale)
            shader.setFloatUniform("uBlur", 1.2f * scale)
            // band 在 demo 里是固定 60px，但我们的气泡比 demo 卡片高得多，
            // 按高度取 30% 让弯折带覆盖边缘一大圈（夹在 60~200px 之间）
            shader.setFloatUniform("uBand",
                (bubble.height * 0.30f).coerceIn(60f * scale, 200f * scale))
            shader.setFloatUniform("uEdgeW", 1.5f * scale)
            shader.setFloatUniform("uEdgeOp", 0.85f)
            shader.setFloatUniform("uLightAng", (PI * 0.78).toFloat())
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "uTex")
                .asComposeRenderEffect()
        }
}

/** 柔光斑（径向渐变替代 demo 里的 ctx.filter='blur(40px)' + ellipse） */
private fun DrawScope.blob(cx: Float, cy: Float, c: Color, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to c,
                0.55f to c.copy(alpha = c.alpha * 0.55f),
                1.00f to c.copy(alpha = 0f)
            ),
            center = Offset(cx, cy),
            radius = r
        ),
        radius = r,
        center = Offset(cx, cy)
    )
}

/**
 * 气泡外壳。两种模式：
 *  - selfPaint = true  ：自己画虹彩玻璃并折射自己（Android 12 及以下兜底）
 *  - selfPaint = false ：背景已被 [liquidRefractBackground] 折射，这里只画玻璃「框」
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    selfPaint: Boolean = true,
    onBounds: ((Rect) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    val selfShader: RuntimeShader? = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(AGSL_SELF_GLASS) }.getOrNull()
        } else null
    }

    val radiusPx = with(density) { corner.toPx() }
    val padPx = with(density) { pad.toPx() }
    val shape = RoundedCornerShape(corner)

    Box(
        modifier.onGloballyPositioned { onBounds?.invoke(it.boundsInParent()) }
    ) {
        // ---- 0) 彩色投影（让玻璃浮起来；demo 无阴影，但白底界面需要一点浮起感）----
        Box(
            Modifier
                .matchParentSize()
                .shadow(
                    elevation = 14.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x3D5A78FF),
                    spotColor = Color(0x4D8A6BFF)
                )
        )

        if (selfPaint) {
            // ---- 兜底：自绘虹彩玻璃（折射自己那层渐变）----
            if (selfShader != null) {
                val s = selfShader
                Box(
                    Modifier
                        .matchParentSize()
                        .onGloballyPositioned { sizePx = it.size }
                        .graphicsLayer {
                            val w = sizePx.width.toFloat()
                            val h = sizePx.height.toFloat()
                            if (w > 0f && h > 0f) {
                                val scale = density.density.coerceAtMost(2f)
                                s.setFloatUniform("uRes", w, h)
                                s.setFloatUniform("uCenter", w / 2f, h / 2f)
                                s.setFloatUniform("uHalf", w / 2f - padPx, h / 2f - padPx)
                                s.setFloatUniform("uRadius", radiusPx)
                                s.setFloatUniform("uStrength", 30f * scale)
                                s.setFloatUniform("uChroma", 14f * scale)
                                s.setFloatUniform("uBlur", 1.0f * scale)
                                s.setFloatUniform("uBand", 80f * scale)
                                s.setFloatUniform("uEdgeW", 1.4f * scale)
                                s.setFloatUniform("uEdgeOp", 0.92f)
                                s.setFloatUniform("uLightAng", (PI * 0.78).toFloat())
                                s.setFloatUniform("uLight", 0.5f)
                                renderEffect = RenderEffect
                                    .createRuntimeShaderEffect(s, "uTex")
                                    .asComposeRenderEffect()
                            }
                        }
                        .background(irisBrush(IRIS_GLASS))
                        .background(reflectBrush())
                )
            } else {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(pad)
                        .clip(shape)
                        .background(irisBrush(IRIS_GLASS))
                        .background(reflectBrush())
                        .border(1.2.dp, Color.White.copy(alpha = 0.85f), shape)
                )
            }
        } else {
            // ---- 框模式：背景已被折射，只补极淡冷调（demo 的 tint，几乎不可见）----
            Box(
                Modifier
                    .matchParentSize()
                    .padding(pad)
                    .clip(shape)
                    .background(Color(0x0DFFFFFF))
            )
        }

        // ---- 玻璃厚度柔光：内圈多层描边，模拟光在玻璃厚处累积 ----
        Box(
            Modifier
                .matchParentSize()
                .padding(pad)
                .drawBehind {
                    val r = CornerRadius(radiusPx, radiusPx)
                    for (i in 0 until 4) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = (0.22f - i * 0.05f).coerceAtLeast(0.02f)),
                            cornerRadius = r,
                            style = Stroke(width = (1.1f + i * 2.2f).dp.toPx())
                        )
                    }
                }
        )

        // ---- 断面描边：上/左亮、下/右暗（玻璃断面高光）----
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
                            Color(0xD9FFFFFF),
                            Color(0x59A8C4FF),
                            Color(0xA6FFFFFF)
                        )
                    ),
                    shape = shape
                )
        )

        // ---- 内容层：用自身尺寸撑开父 Box ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}

/** 虹彩渐变（仅 selfPaint 兜底用）：白 → 淡蓝 → 淡紫 → 淡青 */
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
 * 背景折射着色器：只处理气泡圆角矩形内部，外部 `uTex.eval(p)` 原样返回。
 * 与 glass.frag 逐段对应：
 *   sdRB 圆角矩形 SDF → 梯度 g → t=clamp(-d/band) → k=pow(1-pow(t,0.30),2)
 *   → refr = -g*k*strength（沿法线往里偏，像凸透镜）→ 色散 ca 分 R/G/B 三次采样
 *   → 5 点约 1px 模糊 → 厚度柔亮 → 菲涅尔镜面高光 → 1.5px 细白边
 */
private const val AGSL_BG_REFRACT = """
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
    if (d > 4.0) return uTex.eval(p);

    float e = 1.5;
    float2 g = float2(
        sdScene(p + float2(e, 0.0)) - sdScene(p - float2(e, 0.0)),
        sdScene(p + float2(0.0, e)) - sdScene(p - float2(0.0, e))
    );
    g = g / max(length(g), 0.00001);

    float depth = clamp(-d / max(uBand, 1.0), 0.0, 1.0);
    float k = pow(1.0 - pow(depth, 0.30), 2.0);

    float2 refr = -g * k * uStrength;
    float2 ca = -g * k * uChroma;
    float2 bp = clamp(p + refr, float2(0.0), uRes - float2(1.0));
    float bs = uBlur;

    float3 s = tap3(bp, ca)
        + tap3(bp + float2(bs, 0.0), ca)
        + tap3(bp - float2(bs, 0.0), ca)
        + tap3(bp + float2(0.0, bs), ca)
        + tap3(bp - float2(0.0, bs), ca);
    s = s * 0.2;

    float3 col = s * 1.02 + float3(0.008, 0.014, 0.024);

    float thick = 1.0 - smoothstep(0.0, 22.0, -d);
    col = col + float3(0.08, 0.095, 0.12) * thick * thick * 0.85;

    float2 Ld = float2(cos(uLightAng), sin(uLightAng));
    float dir = max(dot(g, Ld), 0.0);
    float rim = 1.0 - smoothstep(0.0, 10.0, -d);
    col = col + rim * (0.18 + 0.82 * dir * dir) * 0.5;

    float line = 1.0 - smoothstep(0.0, max(uEdgeW, 0.6), abs(d));
    col = mix(col, float3(1.0), line * uEdgeOp);

    return float4(clamp(col, 0.0, 1.0), 1.0);
}
"""

/**
 * 兜底版：折射「自己画的渐变」（selfPaint 用；Android 12 及以下走毛玻璃分支不会到这）。
 */
private const val AGSL_SELF_GLASS = """
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
    float k = pow(1.0 - pow(depth, 0.30), 2.0);

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

    float3 col = s * mix(0.985, 1.015, depth);
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
