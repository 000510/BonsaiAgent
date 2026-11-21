package com.example.bonsaiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.ChatMessage
import com.example.bonsaiagent.MainViewModel

@Composable
fun RouterScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
    // v10.99：消息区固定不动，不随键盘上移（键盘弹出只推输入框）
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // 路由说明卡片
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                "混合路由：简单问题本地直答；模型输出 [CALL_CLOUD] 自动调 DeepSeek；" +
                        "输出 [EXEC: 命令] 在本机执行 shell 并回传结果。",
                Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

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
