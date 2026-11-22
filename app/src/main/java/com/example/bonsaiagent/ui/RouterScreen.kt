package com.example.bonsaiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.ChatMessage
import com.example.bonsaiagent.MainViewModel
import com.example.bonsaiagent.agent.CloudSearch

@Composable
fun RouterScreen(vm: MainViewModel, onBackToChat: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
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
    if (vm.quotaBlocked.value) {
        GlassQuotaBubble(vm, Modifier.align(Alignment.BottomCenter))
    }

    // v7.1：Agent 页也挂上推理状态条（等它跑的时候至少知道在干什么、多快）
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 8.dp)
    ) {
        GenStatusBar(vm)
    }
    }
}

/**
 * v10.136：云端额度耗尽气泡（Agent 页底部）。
 * 液态玻璃外观：四角圆弧 + 半透明白渐变 + 顶部高光亮边 + 细白边框 + 投影。
 * 展开后列出所有中转站，点一下切过去，并把刚才那条任务原地续跑（不用重打一遍）。
 */
@Composable
private fun GlassQuotaBubble(vm: MainViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, bottom = 104.dp, top = 8.dp)
            .clip(shape)
            .shadow(14.dp, shape, ambientColor = Color(0x33000000))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xF5FFFFFF), Color(0xC4EAF2FF))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.78f), shape)
    ) {
        // 顶部一道高光亮边（玻璃反光感）
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0f),
                            Color.White.copy(alpha = 0.95f),
                            Color.White.copy(alpha = 0f)
                        )
                    )
                )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠️", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("您的本站额度已用完，请切换中转站",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111))
                Text("点下面任一中转站，自动继续刚才的任务",
                    fontSize = 11.sp, color = Color(0xFF6B7280))
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开",
                    fontSize = 12.sp, color = Color(0xFF4D6BFE), fontWeight = FontWeight.SemiBold)
            }
        }
        if (expanded) {
            val st = vm.settings.value
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                CloudSearch.PROVIDERS.forEach { p ->
                    val isCur = st.cloudProvider == p.id
                    val hasKey = when (p.id) {
                        "st" -> st.stKey.isNotBlank()
                        "r77625" -> st.r77625Key.isNotBlank()
                        else -> st.xlhKey.isNotBlank()
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isCur) Color(0xFF4D6BFE).copy(alpha = 0.10f) else Color.Transparent
                            )
                            .clickable { vm.retryCloudWith(p.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
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
