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
 * v10.146：整体移植资料包 LiquidGlass-Pack-v2 的 glass.frag（MIT，画心人），
 * 并修正 v10.145 的坐标系误判。
 *
 * v10.145 曾把气泡中心改成 `uRes * 0.5`，理由是「p 是 layer 局部坐标，与 boundsInParent 差一个原点」。
 * 那是**误判**：本 shader 挂在全屏容器（matchParentSize）的 graphicsLayer 上，uRes 是全屏尺寸；
 * 而气泡的父级正是这个全屏 Box，boundsInParent() 与 p 本就是同一坐标系。
 * 改成 uRes*0.5 等于把玻璃画到屏幕正中央 —— 表现为「玻璃一直在中间，文字在底部动，玻璃没跟上」。
 * v10.146 恢复 `uCenter = bubbleBounds.center`（= 气泡实际中心），玻璃随气泡位置/尺寸同步。
 *
 * 移植内容不变（v10.145 的算法，逐行照搬原作）：
 *   SDF → 法线 → 折射衰减 k=(1-t^0.3)^2 → 5 点十字模糊 → 提亮 1.06+冷色 → 顶部冷渐变 →
 *   菲涅尔边缘光 → 细白边 → alpha 合成（内部不透明）。
 * 内部深处 k=0 输出「背景×1.06+冷色」，整片同一公式，无分段交界 → 不再有长方形高亮带。
 * 参数 = 原作参数表默认值：strength 48 / chroma 12 / blur 1.2 / band 60 / edgeW 1.5 /
 *        edgeOp 0.85 / lightAng -2.2 / light 0.35 / frost 0 / mag 1 / str 1 / tint 0（×dpr，dpr≤2）。
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
            // uCenter = 气泡在全屏容器内的实际中心（boundsInParent 与该 layer 的 p 同坐标系）。
            // 气泡位置一变（展开/收起/IME 弹出），这里跟着变 → 玻璃跟着内容走。
            shader.setFloatUniform("uCenter", bubble.center.x, bubble.center.y)
            shader.setFloatUniform("uHalf", bubble.width / 2f, bubble.height / 2f)
            shader.setFloatUniform("uRadius", radiusPx)
            // v10.145：整体移植 Pack-v2 glass.frag。uniform 全部用原作参数表默认值（×dpr，dpr=min(devicePixelRatio,2)）：
            //   uP = (strength 48, chroma 12, blur 1.2, band 60)
            //   uE = (edgeW 1.5, edgeOp 0.85, lightAng -2.2, light 0.35)
            //   frost 0 / mag 1 / str 1 / tint 0
            // 不再做「尺寸自适应 / clampIn / 自定义模糊」——那是导致长方形高亮的自创逻辑，全部移除。
            shader.setFloatUniform("uP", 48f * scale, 12f * scale, 1.2f * scale, 60f * scale)
            shader.setFloatUniform("uE", 1.5f * scale, 0.85f, -2.2f, 0.35f)
            shader.setFloatUniform("uFrost", 0f)
            shader.setFloatUniform("uMag", 1f)
            shader.setFloatUniform("uStr", 1f)
            shader.setFloatUniform("uTint", 0f)
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

        // ---- 内容层：用自身尺寸撑开父 Box ----
        Box(Modifier.fillMaxWidth().padding(pad), content = content)
    }
}

/**
 * 边缘折射着色器（整体移植自「LiquidGlass-Pack-v2 / shader/glass.frag」，画心人，MIT）：
 *   SDF 圆角矩形 → 中心差分法线 g（朝外）→ t=clamp(-d/band) → k=(1-t^0.3)^2
 *   → refr=-g*k*strength（朝中心推）、ca=-g*k*chroma（色散）→ 5 点十字模糊
 *   → col=s*1.06+冷色（整体提亮+冷色）→ 顶部冷色渐变 → 菲涅尔边缘光(定向) → 细白边
 *   → alpha=max(inside,line*edgeOp)，内部不透明（interior alpha=1）
 * 特性：玻璃整体是**均匀的亮体**，不像自研版「边缘折射带压暗、中部平坦区原样」那样
 * 在交界处产生一条与页面同色的「长方形高亮带」。这就是 v10.144 那块的真正来源，
 * 本版不再自编任何公式，逐行照搬原作。
 */
private const val AGSL_EDGE_REFRACT = """
// 移植自 LiquidGlass-Pack-v2 / shader/glass.frag（MIT，画心人）。单元素版本。
// 语义与算法逐行对应原版：
//   SDF 圆角矩形 → 中心差分法线 g → t=clamp(-d/band) → k=(1-t^0.3)^2
//   → refr=-g*k*strength, ca=-g*k*chroma → 5 点十字模糊 → col=s*1.06+冷色
//   → 顶部冷色渐变 → 磨砂/着色 → 菲涅尔边缘光(定向) → 细白边 → alpha 合成(内部不透明)
// 参数 = Pack-v2 参数表默认值（×dpr，dpr=min(devicePixelRatio,2)，Kotlin 侧传入）。
uniform shader uTex;
uniform float2 uRes;
uniform float2 uCenter;
uniform float2 uHalf;
uniform float uRadius;
uniform float4 uP;   // (strength 折射, chroma 色散, blur 模糊px, band 折射带宽px)
uniform float4 uE;   // (edgeWidth 白边px, edgeOpacity, 光源角度rad, 边缘光强度)
uniform float uFrost;
uniform float uMag;
uniform float uStr;
uniform float uTint;

float sdRB(float2 p, float2 b, float r) {
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

// 原版 tap：RGB 分通道采样 + 色散偏移，uv 夹在 [.001,.999]（像素坐标对应 [1, uRes-1]）。
float3 tap(float2 px, float2 ca) {
    float2 u = clamp(px + ca, float2(1.0), uRes - 1.0);
    float2 v = clamp(px,       float2(1.0), uRes - 1.0);
    float2 w = clamp(px - ca,  float2(1.0), uRes - 1.0);
    return float3(uTex.eval(u).r, uTex.eval(v).g, uTex.eval(w).b);
}

float4 glass(float2 p) {
    // 坐标系：本 shader 挂在**全屏容器**（matchParentSize）的 graphicsLayer 上，
    // 所以 p 是全屏局部坐标；气泡的父级正是这个全屏 Box，
    // 故 uCenter = bubbleBounds.center（boundsInParent()）与 p 同坐标系，可直接相减。
    // ⚠️ 切勿用 uRes*0.5 当中心 —— uRes 是全屏尺寸，那会把玻璃画到屏幕正中央。
    float d = sdRB(p - uCenter, uHalf, uRadius);
    if (d > 3.0) return float4(0.0);

    // 朝外法线（中心差分，e=1.5，与原件一致）
    float e = 1.5;
    float2 g = float2(
        sdRB(p + float2(e, 0.0) - uCenter, uHalf, uRadius) - sdRB(p - float2(e, 0.0) - uCenter, uHalf, uRadius),
        sdRB(p + float2(0.0, e) - uCenter, uHalf, uRadius) - sdRB(p - float2(0.0, e) - uCenter, uHalf, uRadius)
    );
    g = normalize(g + 0.000001);

    // 折射衰减：t = -d/band，k = (1 - t^0.3)^2（原件第 19 行）
    float t = clamp(-d / max(uP.w, 1.0), 0.0, 1.0);
    float k = pow(1.0 - pow(t, 0.30), 2.0);

    float2 q = p;
    if (uMag > 1.001) q = uCenter + (p - uCenter) / uMag;
    float2 refr = -g * k * uP.x * uStr;
    float2 ca = -g * k * uP.y * uStr;
    float2 bp = q + refr;
    float bs = uP.z;

    // 5 点模糊（原件第 21 行：中心 + 十字 4 点，半径 bs；不做 clampIn 之类自定义约束）
    float3 s = tap(bp, ca)
             + tap(bp + float2(bs, 0.0), ca)
             + tap(bp - float2(bs, 0.0), ca)
             + tap(bp + float2(0.0, bs), ca)
             + tap(bp - float2(0.0, bs), ca);
    s /= 5.0;

    // 整体提亮 + 冷色（原件第 23 行）
    float3 col = s * 1.06 + float3(0.015, 0.025, 0.048);

    // 顶部冷色渐变（原件第 24 行，越往上越冷/越暗）。
    // 原件写成 smoothstep(+half.y*.35, -half.y*1.1, y)（edge0 > edge1，GLSL 里属未定义），
    // 这里改写为等价的 1-smoothstep(-half.y*1.1, +half.y*.35, y)，语义一致且在 AGSL 下行为确定。
    col += (1.0 - smoothstep(-uHalf.y * 1.1, uHalf.y * 0.35, p.y - uCenter.y)) * float3(0.055, 0.075, 0.105);

    // 磨砂 / 着色（原件 25-26 行；默认 frost=0、tint=0 → 无效果，保留以对齐算法）
    col = mix(col, float3(0.97, 0.98, 1.0), uFrost * 0.85);
    col = mix(col, float3(0.88, 0.94, 1.0), uTint);

    // 菲涅尔边缘光（原件 28-30 行，定向光源角度 uE.z）
    float2 Ld = float2(cos(uE.z), sin(uE.z));
    float rim = 1.0 - smoothstep(0.0, 7.0, -d);
    col += uE.w * rim * pow(max(dot(g, Ld), 0.0), 2.0);
    col += uE.w * 0.45 * rim * pow(max(dot(g, -Ld), 0.0), 3.0);

    // 细白边（原件 32 行）
    float line = 1.0 - smoothstep(0.0, uE.x, abs(d));
    col = mix(col, float3(1.0), line * uE.y);

    // 内部不透明 + 白边 alpha（原件 34-35 行）
    float inside = 1.0 - smoothstep(-0.8, 0.8, d);
    return float4(col, max(inside, line * uE.y));
}

float4 main(float2 p) {
    float4 bg = uTex.eval(p);
    if (sdRB(p - uCenter, uHalf, uRadius) > 3.0) return bg;
    float4 a = glass(p);
    return float4(mix(bg.rgb, a.rgb, a.a), 1.0);
}
"""
