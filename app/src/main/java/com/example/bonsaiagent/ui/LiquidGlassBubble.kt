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
 * v10.140：真折射液态玻璃 —— 照「画心人 LiquidGlass-Pack v2」demo 移植，修掉 v10.139 的致命顺序 bug。
 *
 * 四代踩坑：
 *  - 137：玻璃里画接近白的淡色 + shader 提亮 → 纯白。
 *  - 138：玻璃里画虹彩渐变 → 有颜色，但背后内容纹丝不动。
 *  - 139：照 demo 画了渐变+光斑+淡白横线。但 Modifier 顺序写反了：
 *        `.drawWithContent{画背景}.graphicsLayer{renderEffect}` → 折射层在**里侧**，
 *        渐变画在**外侧**，shader 根本没处理到渐变 → 于是只剩「渐变+白边+高光」的平面贴纸。
 *        正确顺序必须 `.graphicsLayer{renderEffect}.drawWithContent{画背景}`。
 *  - 140（本版）：修顺序 + 加「凝胶感」三件套：
 *        ① 透视放大：玻璃内部按 0.97 系数往中心收，相当于放大 1.03 倍（凸透镜）
 *        ② 厚度高光：顶部白反光带 + 内圈多层柔光 + 上亮下暗断面
 *        ③ 底部内阴影：模拟玻璃厚度压出来的影
 *        + 参照物加粗：淡白横线 alpha 0.55 / 1.8dp / 间距 44dp（折射靠它显形）
 *        + demo 原参数 strength48/chroma12/blur1.2/edgeW1.5/edgeOp0.85 全部×min(dpr,2)
 *
 * 只折射气泡圆角矩形内部；矩形外 `uTex.eval(p)` 原样返回，界面其余部分零影响。
 * Android 12 及以下无 AGSL → 退回自绘虹彩玻璃，不会白板也不会崩。
 */
@Composable
fun Modifier.liquidRefractBackground(
    enabled: Boolean,
    bubble: Rect,
    corner: Dp = 26.dp
): Modifier {
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(AGSL_BG_REFRACT) }.getOrNull()
        } else null
    }
    if (!enabled || shader == null || bubble.width <= 1f || bubble.height <= 1f) return this

    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val lineStep = with(density) { 44.dp.toPx() }
    val lineW = with(density) { 1.8.dp.toPx() }

    return this
        // ① 折射层必须在**外层**，才能包住下面 drawWithContent 画出来的背景
        .graphicsLayer {
            val scale = density.density.coerceAtMost(2f)
            shader.setFloatUniform("uRes", size.width, size.height)
            shader.setFloatUniform("uCenter", bubble.center.x, bubble.center.y)
            shader.setFloatUniform("uHalf", bubble.width / 2f, bubble.height / 2f)
            shader.setFloatUniform("uRadius", radiusPx)
            // demo 默认 strength 48 / chroma 12 / blur 1.2 / edgeW 1.5 / edgeOp 0.85
            shader.setFloatUniform("uStrength", 48f * scale)
            shader.setFloatUniform("uChroma", 12f * scale)
            shader.setFloatUniform("uBlur", 1.2f * scale)
            shader.setFloatUniform("uBand",
                (bubble.height * 0.30f).coerceIn(60f * scale, 200f * scale))
            shader.setFloatUniform("uEdgeW", 1.5f * scale)
            shader.setFloatUniform("uEdgeOp", 0.85f)
            shader.setFloatUniform("uLightAng", (PI * 0.78).toFloat())
            shader.setFloatUniform("uMag", 0.97f)   // 凸透镜放大（<1 = 放大）
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "uTex")
                .asComposeRenderEffect()
        }
        // ② 背景层内容 = 原界面 + 气泡区域内「demo 同款背景」（折射参照物）
        .drawWithContent {
            drawContent()
            if (bubble.width > 1f && bubble.height > 1f) {
                val path = Path().apply {
                    addRoundRect(RoundRect(bubble, CornerRadius(radiusPx, radiusPx)))
                }
                clipPath(path) {
                    // demo 线性渐变
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

                    // demo 的 4 个 blur(40px) 椭圆光斑
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

                    // demo 的淡白横线 —— 折射的「参照物」，被掰弯才看得出玻璃
                    var y = bubble.top + lineStep * 0.3f
                    while (y < bubble.bottom) {
                        drawRect(
                            color = Color(0x8CFFFFFF),
                            topLeft = Offset(bubble.left, y),
                            size = Size(bubble.width, lineW)
                        )
                        y += lineStep
                    }
                }
            }
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
        // ---- 0) 彩色投影（让玻璃浮起来）----
        Box(
            Modifier
                .matchParentSize()
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x425A78FF),
                    spotColor = Color(0x528A6BFF)
                )
        )

        if (selfPaint) {
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
                                s.setFloatUniform("uMag", 0.97f)
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
            // ---- 框模式：背景已被折射。这里只加「玻璃立体感」三件套 ----
            Box(
                Modifier
                    .matchParentSize()
                    .padding(pad)
                    .clip(shape)
                    // ② 顶部白反光带 + 底部冷调（像玻璃表面的光）
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color(0x4DFFFFFF),
                                0.22f to Color(0x1AFFFFFF),
                                0.55f to Color(0x00FFFFFF),
                                1.00f to Color(0x2E9FB8FF)
                            )
                        )
                    )
                    // ③ 底部内阴影：玻璃厚度压出来的影
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.70f to Color(0x00081430),
                                    1.00f to Color(0x1F081430)
                                ),
                                startY = 0f,
                                endY = size.height
                            )
                        )
                    }
            )
        }

        // ---- 玻璃厚度柔光：内圈多层描边 ----
        Box(
            Modifier
                .matchParentSize()
                .padding(pad)
                .drawBehind {
                    val r = CornerRadius(radiusPx, radiusPx)
                    for (i in 0 until 4) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = (0.26f - i * 0.055f).coerceAtLeast(0.02f)),
                            cornerRadius = r,
                            style = Stroke(width = (1.0f + i * 2.0f).dp.toPx())
                        )
                    }
                }
        )

        // ---- 断面描边：上/左亮、下/右暗 ----
        Box(
            Modifier
                .matchParentSize()
                .padding(pad)
                .clip(shape)
                .border(
                    width = 1.3.dp,
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

/** 虹彩渐变（仅 selfPaint 兜底用） */
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
 * 对应 glass.frag：
 *   sdRB 圆角矩形 SDF → 梯度 g → t=clamp(-d/band) → k=pow(1-pow(t,0.30),2)
 *   → 透视放大 uMag → refr=-g*k*strength（沿法线往里偏，像凸透镜）
 *   → 色散 ca 分 R/G/B 三次采样 → 5 点约 1px 模糊
 *   → 厚度柔亮 → 菲涅尔镜面高光 → 1.5px 细白边
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
uniform float uMag;

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

    // 透视放大：内部往中心收 = 图像被放大（凸透镜）
    float2 pp = uCenter + (p - uCenter) * uMag;
    float2 refr = -g * k * uStrength;
    float2 ca = -g * k * uChroma;
    float2 bp = clamp(pp + refr, float2(0.0), uRes - float2(1.0));
    float bs = uBlur;

    float3 s = tap3(bp, ca)
        + tap3(bp + float2(bs, 0.0), ca)
        + tap3(bp - float2(bs, 0.0), ca)
        + tap3(bp + float2(0.0, bs), ca)
        + tap3(bp - float2(0.0, bs), ca);
    s = s * 0.2;

    float3 col = s * 1.02 + float3(0.008, 0.014, 0.024);

    // 玻璃厚度：靠边缘一圈更亮（光在厚处累积）
    float thick = 1.0 - smoothstep(0.0, 22.0, -d);
    col = col + float3(0.09, 0.105, 0.13) * thick * thick * 0.9;

    // 菲涅尔镜面高光：上/左（光源方向）最强
    float2 Ld = float2(cos(uLightAng), sin(uLightAng));
    float dir = max(dot(g, Ld), 0.0);
    float rim = 1.0 - smoothstep(0.0, 10.0, -d);
    col = col + rim * (0.18 + 0.82 * dir * dir) * 0.55;

    // 1.5px 细白边
    float line = 1.0 - smoothstep(0.0, max(uEdgeW, 0.6), abs(d));
    col = mix(col, float3(1.0), line * uEdgeOp);

    return float4(clamp(col, 0.0, 1.0), 1.0);
}
"""

/** 兜底版：折射「自己画的渐变」 */
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
uniform float uMag;

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

    float2 pp = uCenter + (p - uCenter) * uMag;
    float2 refr = -g * k * uStrength;
    float2 ca = -g * k * uChroma;
    float2 bp = pp + refr;
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
