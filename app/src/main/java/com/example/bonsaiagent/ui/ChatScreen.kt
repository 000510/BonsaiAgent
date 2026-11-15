package com.example.bonsaiagent.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.ChatMessage
import com.example.bonsaiagent.MainViewModel

@Composable
fun ChatScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 流式输出时跟随到底部。用最后一条的长度当 key，内容增长就会触发
    val lastLen = vm.chatMessages.lastOrNull()?.content?.value?.length ?: 0
    LaunchedEffect(vm.chatMessages.size, lastLen) {
        if (vm.chatMessages.isNotEmpty()) {
            listState.scrollToItem(vm.chatMessages.size - 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (vm.chatMessages.isEmpty()) {
                EmptyState(Modifier.weight(1f), onPick = { input = it })
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.chatMessages, key = { it.id }) { msg -> Bubble(msg) }
                }
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.sendChat(input)
                        input = ""
                    }
                },
                placeholder = "和本地模型聊聊…"
            )
        }

        // 悬浮状态条：显示推理进度与速度，可拖动、可最小化
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 8.dp)
        ) {
            GenStatusBar(vm)
        }
    }
}

/** 聊天气泡：用户靠右（右下角收尖）、助手靠左（左下角收尖）。长按复制。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val text = msg.content.value
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    val shape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxW = maxWidth * 0.88f
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = maxW)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    ),
                color = if (isUser) cs.primary else cs.surfaceVariant,
                shape = shape
            ) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    when {
                        isUser -> Text(
                            text,
                            color = cs.onPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        text.isBlank() -> Text("…", color = cs.onSurfaceVariant)
                        else -> MarkdownText(text, cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 首页空白状态：别让第一次打开是一片空白 */
@Composable
private fun EmptyState(modifier: Modifier, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Spa,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "BonsaiAgent",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "本地运行 · 离线可用 · 数据不出手机",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        listOf(
            "用一句话介绍一下你自己",
            "把刚才的内容总结成三点",
            "现在几点了"
        ).forEach { s ->
            Surface(
                onClick = { onPick(s) },
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    s,
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

/** 底部输入区：圆角胶囊 + 图标发送按钮。RouterScreen 复用，故为 internal。 */
@Composable
internal fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ───────────────────────── Markdown 轻量渲染 ─────────────────────────
// 不引第三方库，只处理模型实际会输出的几种结构：
//   ```代码块``` / # 标题 / - 列表 / `行内代码` / **粗体**

private sealed class MdBlock {
    data class Para(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
}

private fun parseMd(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.split('\n')
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            out.add(MdBlock.Para(para.toString().trim()))
            para.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trimStart()
        when {
            t.startsWith("```") -> {
                flushPara()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out.add(MdBlock.Code(lang, code.toString().trimEnd('\n')))
            }
            t.startsWith("### ") -> {
                flushPara(); out.add(MdBlock.Heading(3, t.removePrefix("### ")))
            }
            t.startsWith("## ") -> {
                flushPara(); out.add(MdBlock.Heading(2, t.removePrefix("## ")))
            }
            t.startsWith("# ") -> {
                flushPara(); out.add(MdBlock.Heading(1, t.removePrefix("# ")))
            }
            t.startsWith("- ") || t.startsWith("* ") -> {
                flushPara(); out.add(MdBlock.Bullet(t.substring(2)))
            }
            t.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(raw)
            }
        }
        i++
    }
    flushPara()
    return out
}

@Composable
private fun MarkdownText(src: String, color: Color) {
    val blocks = remember(src) { parseMd(src) }
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Code -> CodeBlock(b.lang, b.code)
                is MdBlock.Heading -> Text(
                    inlineAnnotated(b.text, codeBg),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (b.level) {
                        1 -> 17.sp
                        2 -> 16.sp
                        else -> 15.sp
                    },
                    lineHeight = 22.sp
                )
                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text("·", color = color, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        inlineAnnotated(b.text, codeBg),
                        color = color,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MdBlock.Para -> Text(
                    inlineAnnotated(b.text, codeBg),
                    color = color,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.onSurface.copy(alpha = 0.06f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            if (lang.isNotBlank()) {
                Text(
                    lang,
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = cs.onSurface
            )
        }
    }
}

/** 行内语法：`代码` 和 **粗体** */
private fun inlineAnnotated(text: String, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg
                            )
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
    }
