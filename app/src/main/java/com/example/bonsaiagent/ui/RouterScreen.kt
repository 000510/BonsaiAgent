package com.example.bonsaiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.MainViewModel

@Composable
fun RouterScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val lastLen = vm.agentMessages.lastOrNull()?.content?.value?.length ?: 0
    LaunchedEffect(vm.agentMessages.size, lastLen) {
        if (vm.agentMessages.isNotEmpty()) {
            listState.scrollToItem(vm.agentMessages.size - 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 路由说明卡片
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp)
            ) {
                Text(
                    "混合路由：简单问题本地直答；模型输出 [CALL_CLOUD] 自动调 DeepSeek；" +
                            "输出 [EXEC: 命令] 在本机执行 shell 并回传结果。",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (vm.agentMessages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "把任务交给 Agent，它会自己决定用本地还是云端",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.agentMessages, key = { it.id }) { msg -> Bubble(msg) }
                }
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.sendAgent(input)
                        input = ""
                    }
                },
                placeholder = "交给 Agent 处理…"
            )
        }

        // v7.1：Agent 页也挂上推理状态条（等它跑的时候至少知道在干什么、多快）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 8.dp)
        ) {
            GenStatusBar(vm)
        }
    }
}
