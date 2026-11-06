package com.example.bonsaiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bonsaiagent.ChatMessage
import com.example.bonsaiagent.MainViewModel

@Composable
fun ChatScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.chatMessages, key = { it.id }) { msg -> Bubble(msg) }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("和本地模型聊聊…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                vm.sendChat(input)
                input = ""
            }) { Text("发送") }
        }
    }

    // 悬浮状态条：显示推理进度与速度，可拖动、可最小化
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 8.dp)
    ) {
        GenStatusBar(vm)
    }
    }
}

@Composable
fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp
        ) {
            Text(
                text = msg.content.value.ifBlank { "…" },
                modifier = Modifier.padding(10.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
