package com.example.bonsaiagent.ui

import android.widget.Toast
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.ChatMessage
import com.example.bonsaiagent.data.MemoryFact
import com.example.bonsaiagent.MainViewModel

@Composable
fun ChatScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    // v10.99：键盘弹出时，右滑收回输入法（而不是打开历史抽屉）
    val imeVisible = WindowInsets.isImeVisible

    Box(Modifier.fillMaxSize()) {
    // v10.99：消息区固定不动，不随键盘上移；键盘弹出时右滑收输入法
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(imeVisible) {
                if (imeVisible) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onHorizontalDrag = { _, dragAmount -> dx += dragAmount },
                        onDragEnd = { if (dx > 100f) focusManager.clearFocus() }
                    )
                }
            }
    ) {
        if (vm.chatMessages.isEmpty()) {
            // v10.98：克制版空状态 —— 中央一个品牌图形 + 一句小字，不堆卡片
            EmptyHint(Modifier.fillMaxSize())
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.chatMessages, key = { it.id }) { msg -> Bubble(msg) }
            }
        }
    }

    // v10.99：输入区 —— overlay 贴底，imePadding 随键盘上移，页面其余不动
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        // v10.87：检测到可记忆的信息时，从输入框上方滑出确认条
        vm.pendingFact.value?.let { f ->
            MemoryPromptCard(
                fact = f,
                onAccept = { vm.acceptPending(it) },
                onIgnore = { vm.ignorePending() },
                onClose = { vm.dismissPending() }
            )
        }
        // v10.98：输入框 —— 压扁半透明胶囊，发送钮悬浮在内部右侧垂直居中；v10.99 加投影
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Box(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("发消息…") },
                    modifier = Modifier.fillMaxWidth().padding(end = 42.dp),
                    maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
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
                            vm.sendChat(input)
                            input = ""
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp)
                        .size(34.dp)
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
                            fontSize = 17.sp, fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
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
 * v10.98：克制版空状态 —— 参照 DeepSeek「中央品牌图形 + 一句问候」，
 * 但做得更轻：浅蓝圆底 + 叶芽图标 + 一句灰字，不堆卡片、不抢戏。
 * 用户对空态暂无明确想法，先给这个极简方案，不喜欢随时可去。
 */
@Composable
fun EmptyHint(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = cs.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.EnergySavingsLeaf,
                    contentDescription = "Bonsai",
                    tint = cs.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "今天想聊点什么？",
            fontSize = 15.sp,
            color = Color(0xFF9CA3AF)
        )
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

/**
 * v10.87：记忆确认卡。
 *
 * 为什么不直接写入：小模型自动抽取准确率只有七成左右，写错了会一直污染后续对话。
 * 这里把决定权交给用户，并把「来源」显示出来 —— 判断对错的依据是原始那句话，不是模型的转述。
 */
@Composable
private fun MemoryPromptCard(
    fact: MemoryFact,
    onAccept: (String) -> Unit,
    onIgnore: () -> Unit,
    onClose: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(fact.id) { mutableStateOf(fact.content) }
    val cs = MaterialTheme.colorScheme

    Surface(
        color = cs.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✨ 检测到一条记忆",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSecondaryContainer
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("✕", fontSize = 13.sp) }
            }
            Text(
                "【${fact.key}】${fact.content}",
                fontSize = 13.sp,
                color = cs.onSecondaryContainer,
                modifier = Modifier.padding(end = 8.dp)
            )
            if (fact.source.isNotBlank()) {
                Text(
                    "来源：${fact.source}",
                    fontSize = 10.sp,
                    color = cs.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAccept(fact.content) }) { Text("加入", fontSize = 13.sp) }
                TextButton(onClick = { editing = true }) { Text("修改", fontSize = 13.sp) }
                TextButton(onClick = onIgnore) { Text("忽略", fontSize = 13.sp) }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("修改后保存") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("记忆内容") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onAccept(draft)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            }
        )
    }
}
