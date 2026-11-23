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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

    return this
        // 折射层在**外层**，包住下面 drawWithContent 画的内容
        .graphicsLayer {
            val scale = density.density.coerceAtMost(2f)
            shader.setFloatUniform("uRes", size.width, size.height)
            shader.setFloatUniform("uCenter", bubble.center.x, bubble.center.y)
            shader.setFloatUniform("uHalf", bubble.width / 2f, bubble.height / 2f)
            shader.setFloatUniform("uRadius", radiusPx)
            // v10.144：尺寸自适应。
            // 原作卡片 300×190、scale=70 → 最大位移约卡片高度的 37%。
            // 我们气泡只有 ~100~150px 高（半高 50~75px），若照抄 38×density=76px，
            // 位移比半高还大 → 边缘采样点被推到气泡另一侧，折射彻底失真。
            // 所以按气泡高度取比例，并设上限，保证小气泡也表现正常。
            val h = bubble.height
            val w = bubble.width
            val shortSide = minOf(w, h)
            shader.setFloatUniform("uBezel", (shortSide * 0.26f).coerceIn(10.dp.toPx(), 26.dp.toPx()))
            shader.setFloatUniform("uScale", (shortSide * 0.17f).coerceIn(6f, 38f * scale))
            // v10.144：色散从 2 提到 8 —— Pack-v2 参数表推荐 12，超过 20 才显假；
            // 早期压到 2 是因为当时玻璃里全是错误叠加，现在可以按推荐值走。
            shader.setFloatUniform("uChroma", (8f * scale).coerceAtMost(shortSide * 0.05f))
            shader.setFloatUniform("uEdgeW", 1.2f * scale)
            shader.setFloatUniform("uEdgeOp", 0.45f)   // 原作 rim 0.45
            // v10.144：uBlur 从 6 降到 2.2 —— Pack-v2 参数表原话「blur 默认 1.2，超过 3 就变回毛玻璃，建议 ≤1.5」。
            // v10.143 用 6×scale（2 倍屏=12px）严重超标：气泡内那片背景被平均成一块均匀色，
            // 铺在圆角矩形里就是一块「长方形高亮区」—— 用户反馈的问题。
            shader.setFloatUniform("uBlur", 2.2f * scale)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "uTex")
                .asComposeRenderEffect()
        }
        // v10.142：不再画「折射参照横线」——用户觉得莫名其妙，而且现在支持自定义背景，
        // 真实壁纸/照片本身就是最好的参照物。
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
            // 兜底玻璃（低版本）：半透明白 + 细白边 —— 同样贴边，不内缩
            Box(
                Modifier
                    .matchParentSize()
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
        }
        // selfPaint=false：玻璃内部 = 背景原样（已被 shader 折射 + 磨砂），不加任何颜色。
        // v10.143：删掉了原来的 Color(0x1FFFFFFF) 白雾 —— 原作 frost=0，加白雾就成了「贴纸」。

        // ---- 玻璃边缘高光：严格照原作 .rim ----
        // v10.143：v10.142 前这里写了三层 1/3/5dp 的白色描边（内缩 9dp），
        // 在气泡里凭空长出一圈发亮的环 —— 用户说的「中间有一圈明显亮了一度」就是它。
        // 原作 .rim 只有：inset 1px 白 0.45 + 左上 1.5px 斜向 + 右下 1px 更弱，全部纯白，无彩色。
        // 关键：必须贴边（不 padding），才能和 shader 的折射边界对齐。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val r = CornerRadius(radiusPx, radiusPx)
                    // inset 0 0 0 1px rgba(255,255,255,.45)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.45f),
                        cornerRadius = r,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    // inset 1.5px 1.5px 1px rgba(255,255,255,.40) —— 只偏左上，不绕一圈
                    val lit = Color.White.copy(alpha = 0.40f)
                    val w1 = 1.dp.toPx()
                    val off = 1.5.dp.toPx()
                    drawLine(lit, androidx.compose.ui.geometry.Offset(off, w1 / 2f),
                        androidx.compose.ui.geometry.Offset(size.width - off, w1 / 2f), w1)
                    drawLine(lit, androidx.compose.ui.geometry.Offset(w1 / 2f, off),
                        androidx.compose.ui.geometry.Offset(w1 / 2f, size.height - off), w1)
                    // inset -1px -1px 1px rgba(255,255,255,.16) —— 右下更弱
                    val dim = Color.White.copy(alpha = 0.16f)
                    drawLine(dim, androidx.compose.ui.geometry.Offset(off, size.height - w1 / 2f),
                        androidx.compose.ui.geometry.Offset(size.width - off, size.height - w1 / 2f), w1)
                    drawLine(dim, androidx.compose.ui.geometry.Offset(size.width - w1 / 2f, off),
                        androidx.compose.ui.geometry.Offset(size.width - w1 / 2f, size.height - off), w1)
                }
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
uniform float uChroma;
uniform float uEdgeW;
uniform float uEdgeOp;
uniform float uBlur;

float sdRB(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

float sdScene(float2 p) {
    return sdRB(p - uCenter, uHalf, uRadius);
}

/** v10.144：把采样点夹在气泡内部。
 *  之前只 clamp 到全屏 —— 模糊半径在 3 倍屏上会到 18px、对角 36px，
 *  采样点跨出气泡边界，把「气泡外那些没被模糊的清晰内容」平均进来，
 *  于是气泡中间凭空出现一块长方形亮带（用户反馈的问题就是它）。 */
float2 clampIn(float2 q, float margin) {
    float2 lim = max(uHalf - float2(margin), float2(1.0));
    return uCenter + clamp(q - uCenter, -lim, lim);
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
    // v10.144：换用 Pack-v2 glass.frag 的衰减曲线 k = (1 - t^0.3)^2。
    // 相比原作的 (1-t)^3，这条在边缘外沿衰减极快 —— t=0.1 处原作还有 0.73，它只剩 0.25，
    // 折射带更「薄」、更贴边，不会往里糊成一片肉感。
    float m = pow(1.0 - pow(t, 0.30), 2.0);
    float2 refr = -g * m * uScale;           // 朝玻璃中心推

    float2 base = p + refr;
    float2 ca = -g * m * uChroma;

    // 色散与模糊的采样同样夹在气泡内，避免越界串味
    float margin = uBlur * 2.2 + 2.0;
    float2 bq = clampIn(base, margin);
    float2 cq = clampIn(base, margin);

    float4 a = uTex.eval(clampIn(base + ca, margin));
    float4 c = uTex.eval(cq);
    float4 b = uTex.eval(clampIn(base - ca, margin));

    float3 col = float3(a.r, c.g, b.b);

    // v10.143：移植 Pack-v2 glass.frag 第 21-22 行的五点模糊（原作 uP.z = blur），
    // 并叠两层半径（bs、bs*1.5）让内部真正「磨砂」——上面的文字才浮得起来；
    // 不模糊就是「一层透明贴纸」，字怎么调颜色都糊在背景里。
    // 只模糊内部：边缘那圈的折射要保留锐利，所以混合权重用 m（靠边 m→1 → 保留折射）。
    if (uBlur > 0.5) {
        float bs = uBlur;
        float3 s = col * 0.0;
        float wsum = 0.0;
        // 半径 1x 十字 4 点
        s += uTex.eval(clampIn(bq + float2(bs, 0.0), margin)).rgb; wsum += 1.0;
        s += uTex.eval(clampIn(bq - float2(bs, 0.0), margin)).rgb; wsum += 1.0;
        s += uTex.eval(clampIn(bq + float2(0.0, bs), margin)).rgb; wsum += 1.0;
        s += uTex.eval(clampIn(bq - float2(0.0, bs), margin)).rgb; wsum += 1.0;
        // 半径 1.5x 对角 4 点（补大颗粒，糊得更实；1.5 而不是 2，免得跨度太大）
        float b2 = bs * 1.5;
        s += uTex.eval(clampIn(bq + float2(b2, b2), margin)).rgb; wsum += 1.0;
        s += uTex.eval(clampIn(bq + float2(b2, -b2), margin)).rgb; wsum += 1.0;
        s += uTex.eval(clampIn(bq + float2(-b2, b2), margin)).rgb; wsum += 1.0;
        s += uTex.eval(clampIn(bq + float2(-b2, -b2), margin)).rgb; wsum += 1.0;
        // 中心原值权重 2，保证整体亮度不塌
        s += col * 2.0; wsum += 2.0;
        s /= wsum;
        col = mix(s, col, m);
    }

    // 白边：v10.144 改用暖白（拖拽实验台的 touchGlow 注释：纯白会发灰发蓝）
    // 用预乘前的 alpha 混合，避免白边把边缘提亮成一整条
    float line = 1.0 - smoothstep(0.0, max(uEdgeW, 0.5), abs(d));
    col = mix(col, float3(1.0, 0.96, 0.90), line * uEdgeOp * 0.8);

    return float4(clamp(col, 0.0, 1.0), c.a);
}
"""
