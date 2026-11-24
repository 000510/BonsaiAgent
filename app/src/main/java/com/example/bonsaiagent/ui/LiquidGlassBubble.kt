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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v10.148：液态玻璃总修复（对应截图「弹框中间一块白色、没有玻璃感」）。
 *
 * 本版修了四件事：
 *
 * 1)【致命】AGSL 里 `vec3` 是 GLSL 类型，AGSL（SkSL）里只有 float3 —— 原来第 238 行
 *    `dot(s, vec3(0.299, 0.587, 0.114))` 会让 RuntimeShader **编译直接抛异常**，
 *    runCatching 吞掉后 shader == null → Android 13+ 的折射层从来没跑起来过。
 *    现已改为 float3，shader 才真正生效。
 *
 * 2)【对齐】旧版用 boundsInParent() 量气泡，但卡片 modifier 链上挂着
 *    imePadding + padding(12/12/8/104)，量出来的矩形会把外层 padding 也算进去
 *    → 玻璃比卡片大一圈、错位，形成「灰框套白块」的两层矩形观感。
 *    现在卡片和折射层两边都用 positionInWindow()/boundsInWindow() 量（窗口坐标系），
 *    在 shader 里相减换算成本层局部坐标 —— 语义唯一，永远和卡片严丝合缝。
 *
 * 3)【材质】纯色浅背景上，即使 shader 跑起来，玻璃内部=采样纯色 → 仍是一块平色卡。
 *    参照 Pack-v2 + iOS 玻璃材质，给玻璃内部补三层「自带质感」：
 *      · 厚度明暗（顶部微亮、底部微暗）
 *      · 内侧高光圈（inset highlight，距边缘一圈细白光）
 *      · 微噪点（打破纯色平涂，±2/255，图片背景下不可感知）
 *    原作算法（SDF/折射/色散/五点模糊/顶部冷渐变/菲涅尔/细白边）逐行保留。
 *
 * 4)【兜底】Android 12L 及以下没有 RuntimeShader，旧兜底是「白渐变实卡」= 白色块本体。
 *    换成低透明度磨砂片 + 顶部内侧高光 + 细白边（配合 RouterScreen 在 12/12L 上
 *    给整个背景加真实 blur，同样有玻璃感）。
 */
class GlassAnchor {
    /** 折射层（matchParentSize 全屏容器）左上角在窗口中的位置 */
    var layerOrigin by mutableStateOf(Offset.Zero)
    /** 玻璃卡片（视觉矩形）在窗口中的位置。由 LiquidGlassCard 量内容框再向外补 pad 得到 */
    var cardRect by mutableStateOf(Rect.Zero)
}

@Composable
fun Modifier.liquidRefractBackground(
    enabled: Boolean,
    anchor: GlassAnchor,
    corner: Dp = 26.dp
): Modifier {
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(AGSL_EDGE_REFRACT) }.getOrNull()
        } else null
    }
    if (!enabled || shader == null) return this

    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }

    return this
        // 折射层自己的窗口位置（用来把卡片窗口坐标换算成本层局部坐标）
        .onGloballyPositioned { coords ->
            anchor.layerOrigin = with(coords.positionInWindow()) { Offset(x.toFloat(), y.toFloat()) }
        }
        .graphicsLayer {
            val card = anchor.cardRect
            if (card.width <= 1f || card.height <= 1f) return@graphicsLayer
            val scale = density.density.coerceAtMost(2f)
            shader.setFloatUniform("uRes", size.width, size.height)
            // 窗口坐标 → 本 layer 局部坐标（两边同在窗口坐标系里量，相减即得局部中心）
            shader.setFloatUniform("uCenter", card.center.x - anchor.layerOrigin.x, card.center.y - anchor.layerOrigin.y)
            shader.setFloatUniform("uHalf", card.width / 2f, card.height / 2f)
            shader.setFloatUniform("uRadius", radiusPx)
            // Pack-v2 参数表默认值（×dpr，dpr=min(devicePixelRatio,2)）：
            //   uP = (strength 48, chroma 12, blur 1.2, band 60)
            //   uE = (edgeW 1.5, edgeOp 0.85, lightAng -2.2, light 0.35)
            shader.setFloatUniform("uP", 48f * scale, 12f * scale, 1.2f * scale, 60f * scale)
            shader.setFloatUniform("uE", 1.5f * scale, 0.85f, -2.2f, 0.35f)
            // v10.148 新增：材质参数 (内侧高光圈的内缩px, 高光强度)
            shader.setFloatUniform("uMat", 4.5f * scale, 0.30f)
            shader.setFloatUniform("uFrost", 0f)
            shader.setFloatUniform("uMag", 1f)
            shader.setFloatUniform("uStr", 1f)
            shader.setFloatUniform("uTint", 0f)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "uTex")
                .asComposeRenderEffect()
        }
}

/**
 * 气泡外壳。
 *  - selfPaint = true  ：Android 12L 及以下兜底（低透明度磨砂玻璃片）
 *  - selfPaint = false ：背景已被 [liquidRefractBackground] 折射，这里只画玻璃「框」
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    pad: Dp = 9.dp,
    selfPaint: Boolean = true,
    anchor: GlassAnchor? = null,
    onBounds: ((Rect) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val padPx = with(density) { pad.toPx() }
    val shape = RoundedCornerShape(corner)

    Box(modifier) {
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
            // v10.148 兜底玻璃 v2：低透明度磨砂片 + 顶部内侧高光 + 细白边。
            // 旧版 0x8CFFFFFF(白55%) 起头的渐变在浅色背景上就是一块白卡 —— 这就是截图里的白色块。
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .drawBehind {
                        // 顶部内侧高光：玻璃上缘受光的一圈弧
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)
                            ),
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(radiusPx - stroke, radiusPx - stroke),
                            style = Stroke(width = stroke)
                        )
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x59FFFFFF),  // 白 35%
                                Color(0x47F4F7FF),  // 28%
                                Color(0x38E3EAFF)   // 22%
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.75f), shape)
            )
        }
        // selfPaint=false：玻璃内部 = 背景原样（已被 shader 折射 + 材质），不加任何颜色。

        // ---- 内容层：量“内容框”，向外补 pad → 得到玻璃视觉矩形 ----
        // 用 boundsInWindow() 而不是 boundsInParent()：窗口坐标系语义唯一，
        // 不会被 padding/imePadding 等 modifier 包装坑到（v10.148 对齐修复）。
        Box(
            Modifier
                .fillMaxWidth()
                .padding(pad)
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    val r = Rect(b.left - padPx, b.top - padPx, b.right + padPx, b.bottom + padPx)
                    if (anchor != null) anchor.cardRect = r
                    onBounds?.invoke(r)
                },
            content = content
        )
    }
}

/**
 * 边缘折射着色器（整体移植自「LiquidGlass-Pack-v2 / shader/glass.frag」，画心人，MIT）：
 *   SDF 圆角矩形 → 法线 g（朝外）→ t=clamp(-d/band) → k=(1-t^0.3)^2
 *   → refr=-g*k*strength（朝中心推）、ca=-g*k*chroma（色散）→ 5 点十字模糊
 *   → col=s*(亮度自适应)+冷色 → 顶部冷色渐变 → 材质(厚度/内侧高光/噪点) → 菲涅尔边缘光(定向) → 细白边
 *   → alpha=max(inside,line*edgeOp)，内部不透明（interior alpha=1）
 * 特性：玻璃整体是**均匀的亮体**，不像自研版「边缘折射带压暗、中部平坦区原样」那样
 * 在交界处产生一条与页面同色的「长方形高亮带」。
 */
private const val AGSL_EDGE_REFRACT = """
// 移植自 LiquidGlass-Pack-v2 / shader/glass.frag（MIT，画心人）。单元素版本。
// 语义与算法逐行对应原版：
//   SDF 圆角矩形 → 中心差分法线 g → t=clamp(-d/band) → k=(1-t^0.3)^2
//   → refr=-g*k*strength, ca=-g*k*chroma → 5 点十字模糊 → col=s*1.06+冷色
//   → 顶部冷色渐变 → 磨砂/着色 → 材质补强 → 菲涅尔边缘光(定向) → 细白边 → alpha 合成(内部不透明)
// 参数 = Pack-v2 参数表默认值（×dpr，dpr=min(devicePixelRatio,2)，Kotlin 侧传入）。
// ⚠️ AGSL 是 SkSL 语法：只有 float2/float3/float4，没有 vec3 —— 旧版这里写 vec3 导致
//    RuntimeShader 编译失败、折射层整个失效（v10.148 修复）。
uniform shader uTex;
uniform float2 uRes;
uniform float2 uCenter;
uniform float2 uHalf;
uniform float uRadius;
uniform float4 uP;   // (strength 折射, chroma 色散, blur 模糊px, band 折射带宽px)
uniform float4 uE;   // (edgeWidth 白边px, edgeOpacity, 光源角度rad, 边缘光强度)
uniform float2 uMat; // v10.148: (内侧高光圈内缩px, 高光强度)
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
    // 坐标系：uCenter/uHalf 由 Kotlin 侧把「卡片窗口坐标」换算到本层局部坐标后传入，
    // 与 p 同坐标系，可直接相减。
    // ⚠️ 切勿用 uRes*0.5 当中心 —— 那会把玻璃画到屏幕正中央（v10.145 的坑）。
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

    // 5 点模糊（原件第 21 行：中心 + 十字 4 点，半径 bs）
    float3 s = tap(bp, ca)
             + tap(bp + float2(bs, 0.0), ca)
             + tap(bp - float2(bs, 0.0), ca)
             + tap(bp + float2(0.0, bs), ca)
             + tap(bp - float2(0.0, bs), ca);
    s /= 5.0;

    // v10.147：背景自适应对比（原件第 23 行「×1.06 提亮」在浅色/纯色背景上会过曝成纯白实卡）。
    // 暗/中背景(luma<0.55)保持原版 1.06（风景图效果不变）；亮背景(luma>0.85)压暗到 0.88 + 撤冷色。
    float luma = dot(s, float3(0.299, 0.587, 0.114));
    float boost = mix(1.06, 0.88, smoothstep(0.55, 0.85, luma));
    float3 col = s * boost + float3(0.015, 0.025, 0.048) * (1.0 - smoothstep(0.55, 0.85, luma));

    // 顶部冷色渐变（原件第 24 行；原件 smoothstep(+a,-b) 逆序在 AGSL 未定义，改写为等价形式）
    col += (1.0 - smoothstep(-uHalf.y * 1.1, uHalf.y * 0.35, p.y - uCenter.y)) * float3(0.055, 0.075, 0.105);

    // 磨砂 / 着色（原件 25-26 行；默认 frost=0、tint=0 → 无效果，保留以对齐算法）
    col = mix(col, float3(0.97, 0.98, 1.0), uFrost * 0.85);
    col = mix(col, float3(0.88, 0.94, 1.0), uTint);

    // ── v10.148 材质补强：纯色背景上也能看出「玻璃片」────────────────
    // 1) 厚度明暗：顶部微亮、底部微暗（受光体积感）
    float yn = clamp((p.y - (uCenter.y - uHalf.y)) / max(uHalf.y * 2.0, 1.0), 0.0, 1.0);
    col *= (1.03 - 0.07 * yn);
    // 2) 内侧高光圈：距边缘 uMat.x 像素处一圈细白光（iOS 玻璃的 inset highlight）
    float ring = (1.0 - smoothstep(0.8, 2.4, abs(d + uMat.x))) * uMat.y;
    col += float3(ring);
    // 3) 微噪点：打破纯色平涂（±2/255，图片背景下不可感知）
    float grain = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    col += (grain - 0.5) * 0.016;

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
