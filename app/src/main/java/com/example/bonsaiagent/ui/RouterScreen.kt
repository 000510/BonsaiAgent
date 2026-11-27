package com.example.bonsaiagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.MainViewModel
import com.example.bonsaiagent.data.AgentSettings

@Composable
fun RouterScreen(vm: MainViewModel, onBackToChat: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
    // v10.161：调试开关 —— 一键弹出「云端调用失败」气泡。
    // 之前每次都要等模型加载完、再等云端真的报 401 才能看到气泡，调试玻璃太费时间。
    // 这里用本地 state 直接叠加，不碰 vm.quotaBlocked 的真实逻辑（不影响切站/重试）。
    var debugBubble by remember { mutableStateOf(false) }
    val blocked = vm.quotaBlocked.value || debugBubble
    // v10.173：背景层在窗口中的位置/尺寸（糊化背板的定位基准）
    // v10.174：玻璃卡糊化背板要用设置（壁纸路径）—— GLM 包漏了这行，导致调用处 settings 未定义
    val settings = vm.settings.value
    val bgFrame = remember { mutableStateOf(Rect.Zero) }

    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned {
                bgFrame.value = Rect(
                    it.positionInWindow(),
                    Size(it.size.width.toFloat(), it.size.height.toFloat())
                )
            }
    ) {
    RouterChatBody(vm, listState)
    }

    // v10.99：输入框 overlay 贴底 + imePadding，键盘弹出只推输入框；加投影
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("交给 Agent 处理…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            val canSend = input.isNotBlank()
            IconButton(
                onClick = {
                    if (canSend) {
                        vm.sendAgent(input)
                        input = ""
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .padding(bottom = 2.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                enabled = canSend
            ) {
                Text(
                    "↑",
                    color = if (canSend) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp, fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }

    // v10.136：云端额度耗尽 → 底部液态玻璃气泡（可展开中转站列表，点一下切站续跑）
    if (blocked) {
        GlassQuotaBubble(
            vm = vm,
            modifier = Modifier.align(Alignment.BottomCenter),
            backdropSettings = settings,
            bgOrigin = bgFrame.value.topLeft,
            bgSize = IntSize(
                bgFrame.value.width.toInt().coerceAtLeast(1),
                bgFrame.value.height.toInt().coerceAtLeast(1)
            )
        )
    }

    // v7.1：Agent 页也挂上推理状态条（等它跑的时候至少知道在干什么、多快）
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 8.dp)
    ) {
        GenStatusBar(vm)
    }

    // v10.161：调试入口 —— 点一下直接弹「云端调用失败」气泡（不用等模型加载/云端报错）
    Surface(
        onClick = { debugBubble = !debugBubble },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 56.dp, end = 10.dp),
        shape = RoundedCornerShape(50),
        color = if (debugBubble) Color(0xE64D6BFE) else Color(0xA6222222)
    ) {
        Text(
            if (debugBubble) "关气泡" else "测气泡",
            fontSize = 11.sp,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }

    }
}

/**
 * v10.154：背景层内容（背景 + WORK 标识 + 对话列表）。
 * 真实层与（若启用的）卡内镜像共用同一份代码和同一个滚动状态。
 */
@Composable
private fun RouterChatBody(vm: MainViewModel, listState: LazyListState) {
    // v10.142：界面背景（自定义图片/纯色）—— 液态玻璃折射的就是这一层
    AppBackground(vm.settings.value)
    // v10.99：消息区固定不动，不随键盘上移（键盘弹出只推输入框）
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // v10.107：WORK 艺术字标识——证明当前是 Agent 模式（替代原返回入口/路由说明）
        Text(
            "W O R K",
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = Color(0xFF4D6BFE),
            letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.agentMessages, key = { it.id }) { msg -> Bubble(msg) }
        }
    }
}

/**
 * v10.136：云端额度耗尽气泡（Agent 页底部）。
 * 液态玻璃外观：四角圆弧 + 半透明白渐变 + 顶部高光亮边 + 细白边框 + 投影。
 * 展开后列出所有中转站，点一下切过去，并把刚才那条任务原地续跑（不用重打一遍）。
 */

@Composable
private fun GlassQuotaBubble(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    // v10.174：玻璃卡糊化背板需要「背景原点 + 尺寸」，用来把壁纸按卡片位置裁剪
    backdropSettings: AgentSettings? = null,
    bgOrigin: Offset = Offset.Zero,
    bgSize: IntSize = IntSize.Zero
) {
    var expanded by remember { mutableStateOf(false) }

    // v10.172：回滚 v10.157 玻璃观感 —— 固定白磨砂卡（40/32/25%）任何背景下
    // 深字都清楚，撤掉自适应配色与标题底衬（这些是 158+ 背景副本路线的配套，
    // 该路线已按用户要求整体回滚）。settings 保留给列表使用。
    val settings = vm.settings.value

    // 整卡按压：缩一点 + 弹簧回弹（iOS「按下有回应」的轻量版）
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 820f),
        label = "cardPress"
    )
    // 展开按钮箭头旋转
    val arrowSpin by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "arrowSpin"
    )

    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        // v10.174：把糊化背板参数真正接到卡片上（GLM 包漏了这三行，糊化层永远不会画）
        backdropSettings = backdropSettings,
        bgOrigin = bgOrigin,
        bgSize = bgSize
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ---- 标题行 ----
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚠️", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "云端调用失败，请切换中转站",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3A4048),
                        letterSpacing = 0.2.sp,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color(0x8CFFFFFF),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1.2f),
                                blurRadius = 1.6f
                            )
                        )
                    )
                    val err = vm.lastCloudError.value
                    Text(
                        if (err.isNotBlank()) err.take(90) else "点下面任一站，自动继续刚才的任务",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF5E656E),
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color(0x66FFFFFF),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                blurRadius = 1.2f
                            )
                        )
                    )
                }
                // 展开/收起：胶囊按钮 + 箭头旋转 + 按压高亮
                Surface(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF4D6BFE).copy(alpha = if (expanded) 0.18f else 0.10f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (expanded) "收起" else "展开",
                            fontSize = 12.sp,
                            color = Color(0xFF4D6BFE),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "⌄",
                            fontSize = 11.sp,
                            color = Color(0xFF4D6BFE),
                            modifier = Modifier.graphicsLayer { rotationZ = arrowSpin }
                        )
                    }
                }
            }

            // ---- 中转站列表：弹簧展开/收起 + 淡入 ----
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(durationMillis = 200, delayMillis = 60)),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(durationMillis = 120))
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
                    settings.providers.forEach { p ->
                        val isCur = settings.cloudProvider == p.id
                        val hasKey = p.key.isNotBlank()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))

                                .background(
                                    if (isCur) Color(0xFF4D6BFE).copy(alpha = 0.10f)
                                    else Color.Transparent
                                )
                                .clickable { if (!isCur && hasKey) vm.retryCloudWith(p.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isCur) Color(0xFF4D6BFE) else Color(0xFF111111))
                            Spacer(Modifier.weight(1f))
                            val tip = when {
                                isCur -> "当前站"
                                !hasKey -> "未填Key"
                                else -> "点此切换"
                            }
                            Text(tip, fontSize = 11.sp,
                                color = if (isCur) Color(0xFF4D6BFE)
                                else if (hasKey) Color(0xFF16A34A) else Color(0xFFDC2626))
                        }
                    }
                }
            }
        }
    }
}
