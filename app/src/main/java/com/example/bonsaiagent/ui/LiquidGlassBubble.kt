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
 * v10.141：透明液态玻璃 —— 按资料包「透明液态玻璃_边缘折射」的原原理重做（这是关键的一次方向纠正）。
 *
 * 原作注释（逐字）：
 *   1) 镜片：玻璃里放一份和页面背景完全对齐的背景（看起来就是"透明"）
 *   2) 位移图：红色通道 = 横向推多少，绿色通道 = 竖向推多少
 *      中间是 128（不推），越靠近边缘推得越多，方向朝玻璃中心
 *   3) 按这张图把背景挪一挪 → 边缘一圈被"弯"了
 *   原作参数：bezel 26（边缘带宽）/ scale 70（最大位移≈35px）/ curve 3 / blur 0 / rim 0.45
 *
 * v10.137~140 全部错在同一个地方：往玻璃里塞彩色渐变、提亮、色散、光斑。
 * 那会让玻璃变成「一张彩色图片」。透明玻璃的定义是：**内部 1:1 复制背景，只把边缘一圈推弯**。
 *
 * 本版：
 *  - 背景层在气泡矩形内**不加任何颜色**，只放几条极淡白色横线当折射参照物
 *    （原作背景是照片所以看得见弯折；我们界面是白底，得有根线才看得出"被弯了"）
 *  - shader：内部完全原样复制；只有距边缘 bezel(26dp) 以内沿法线朝中心推，
 *    推的量 = pow(1 - t, curve) × scale，t = 距离/bezel，curve=3 —— 与作图的位移图同一条曲线
 *  - 极轻色散 2px（原作无，加一点点让边缘更"实"）+ 1.2px 白边
 *  - 玻璃的"存在感"交给外面那圈：断面描边 + 内圈柔光 + 中性投影（原作是 0 14px 40px 黑投影）
 */
@Composable
fun Modifier.liquidRefractBackground(
    enabled: Boolean,
    bubble: Rect,
    corner: Dp = 26.dp
): Modifier {
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(AGSL_EDGE_REFRACT) }.getOrNull()
        } else null
    }
    if (!enabled || shader == null || bubble.width <= 1f || bubble.height <= 1f) return this

    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val lineStep = with(density) { 40.dp.toPx() }
    val lineW = with(density) { 1.5.dp.toPx() }

    return this
        // 折射层在**外层**，包住下面 drawWithContent 画的内容
        .graphicsLayer {
            val scale = density.density.coerceAtMost(2f)
            shader.setFloatUniform("uRes", size.width, size.height)
            shader.setFloatUniform("uCenter", bubble.center.x, bubble.center.y)
            shader.setFloatUniform("uHalf", bubble.width / 2f, bubble.height / 2f)
            shader.setFloatUniform("uRadius", radiusPx)
            // 原作：bezel 26 / 最大位移 ≈35px / curve 3
            shader.setFloatUniform("uBezel", 26.dp.toPx())
            shader.setFloatUniform("uScale", 35f * scale)
            shader.setFloatUniform("uCurve", 3.0f)
            shader.setFloatUniform("uChroma", 2.0f * scale)
            shader.setFloatUniform("uEdgeW", 1.2f * scale)
            shader.setFloatUniform("uEdgeOp", 0.45f)   // 原作 rim 0.45
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "uTex")
                .asComposeRenderEffect()
        }
        // 背景层：真实界面内容 + 气泡矩形内几条极淡白横线（唯一的折射参照物，不着色）
        .drawWithContent {
            drawContent()
            if (bubble.width > 1f && bubble.height > 1f) {
                val path = Path().apply {
                    addRoundRect(RoundRect(bubble, CornerRadius(radiusPx, radiusPx)))
                }
                clipPath(path) {
                    var y = bubble.top + lineStep * 0.3f
                    while (y < bubble.bottom) {
                        drawRect(
                            color = Color(0x38FFFFFF),
                            topLeft = Offset(bubble.left, y),
                            size = Size(bubble.width, lineW)
                        )
                        y += lineStep
                    }
                }
            }
        }
}

/**
 * 气泡外壳。
 *  - selfPaint = true  ：Android 12 及以下兜底（自绘半透明白玻璃）
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
    val radiusPx = with(density) { corner.toPx() }
    val shape = RoundedCornerShape(corner)

    Box(
        modifier.onGloballyPositioned { onBounds?.invoke(it.boundsInParent()) }
    ) {
        // ---- 0) 投影：中性黑（原作 0 14px 40px rgba(0,0,0,.28)）----
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

        if (selfPaint) {
            // 兜底玻璃（低版本）：半透明白 + 细白边
            Box(
                Modifier
                    .matchParentSize()
                    .padding(pad)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x8CFFFFFF),
                                Color(0x66F2F6FF),
                                Color(0x4DDFE8FF)
                            )
                        )
                    )
                    .border(1.2.dp, Color.White.copy(alpha = 0.85f), shape)
            )
        } else {
            // 框模式：内部已经是背景原样（透明玻璃），只补极淡的白雾让文字更好读
            Box(
                Modifier
                    .matchParentSize()
                    .padding(pad)
                    .clip(shape)
                    .background(Color(0x1FFFFFFF))
            )
        }

        // ---- 玻璃厚度柔光：内圈多层描边（原作 .rim 的 inset 亮边）----
        Box(
            Modifier
                .matchParentSize()
                .padding(pad)
                .drawBehind {
                    val r = CornerRadius(radiusPx, radiusPx)
                    for (i in 0 until 3) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = (0.22f - i * 0.06f).coerceAtLeast(0.02f)),
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
                    width = 1.1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFFFFFFFF),
                            Color(0xCCFFFFFF),
                            Color(0x4DA8C4FF),
                            Color(0x99FFFFFF)
                        )
                    ),
                    shape = shape
                )
        )

        // ---- 内容层：用自身尺寸撑开父 Box ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}

/**
 * 边缘折射着色器（照「透明液态玻璃_边缘折射」的位移图公式）：
 *   t = 距边缘距离 / bezel（0=边上，1=进入 bezel 以后）
 *   m = pow(1 - t, curve)                  ← 边缘 1，往里迅速衰减到 0
 *   refr = -g * m * scale                  ← g 朝外，取负=朝玻璃中心推（原作位移图方向）
 * 内部 m=0 → 原样复制背景（所以看起来是「透明」的）；只有边缘一圈被弯。
 */
private const val AGSL_EDGE_REFRACT = """
uniform shader uTex;
uniform float2 uRes;
uniform float2 uCenter;
uniform float2 uHalf;
uniform float uRadius;
uniform float uBezel;
uniform float uScale;
uniform float uCurve;
uniform float uChroma;
uniform float uEdgeW;
uniform float uEdgeOp;

float sdRB(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

float sdScene(float2 p) {
    return sdRB(p - uCenter, uHalf, uRadius);
}

float4 main(float2 p) {
    float d = sdScene(p);
    if (d > 2.0) return uTex.eval(p);

    // 朝外的法线
    float e = 1.2;
    float2 g = float2(
        sdScene(p + float2(e, 0.0)) - sdScene(p - float2(e, 0.0)),
        sdScene(p + float2(0.0, e)) - sdScene(p - float2(0.0, e))
    );
    g = g / max(length(g), 0.00001);

    float t = clamp(-d / max(uBezel, 1.0), 0.0, 1.0);
    float m = pow(1.0 - t, uCurve);          // 原作：中间 0，越靠边越大
    float2 refr = -g * m * uScale;           // 朝玻璃中心推

    float2 base = p + refr;
    float2 ca = -g * m * uChroma;

    float4 a = uTex.eval(clamp(base + ca, float2(0.0), uRes - float2(1.0)));
    float4 c = uTex.eval(clamp(base, float2(0.0), uRes - float2(1.0)));
    float4 b = uTex.eval(clamp(base - ca, float2(0.0), uRes - float2(1.0)));

    float3 col = float3(a.r, c.g, b.b);

    // 1.2px 细白边（原作 .rim 的 inset 1px 亮边）
    float line = 1.0 - smoothstep(0.0, max(uEdgeW, 0.5), abs(d));
    col = mix(col, float3(1.0), line * uEdgeOp);

    return float4(clamp(col, 0.0, 1.0), c.a);
}
"""
