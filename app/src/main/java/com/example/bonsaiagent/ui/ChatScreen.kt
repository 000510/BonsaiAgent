package com.example.bonsaiagent.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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

/**
 * 聊天气泡。
 *
 * v10.83：用户消息右下角收尖、助手左下角收尖（模仿对话方向），
 * 最大宽度 320dp 避免长消息顶满整行，长按整条复制。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val text = msg.content.value
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme

    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                ),
            color = if (isUser) cs.primary else cs.surfaceVariant,
            shape = shape,
            tonalElevation = 2.dp
        ) {
            Text(
                text = text.ifBlank { "…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) cs.onPrimary else cs.onSurfaceVariant
            )
        }
    }
}
