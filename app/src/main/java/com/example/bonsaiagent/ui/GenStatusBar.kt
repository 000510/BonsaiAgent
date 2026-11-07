package com.example.bonsaiagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 推理状态条（悬浮、可拖动、可最小化）。
 *
 * 取代原来铺在对话下方的实时日志：日志用来排查，日常用不需要看那么多，
 * 只要知道"现在在干什么、多快"就够了。想看完整日志去设置页。
 *
 * - 拖动：按住整条拖
 * - 最小化：右侧 "－" 按钮，缩成一个显示速度的小圆点
 * - 空闲（就绪）时自动不显示；生成完成后 4 秒自动隐去
 */
@Composable
fun GenStatusBar(vm: MainViewModel) {
    val st = vm.genStatus.value
    if (st.phase == "就绪") return

    var minimized by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 完成后 4 秒自动隐藏，免得一直挡着屏幕
    LaunchedEffect(st.phase) {
        if (st.phase == "完成") {
            delay(4000)
            if (vm.genStatus.value.phase == "完成") {
                vm.genStatus.value = vm.genStatus.value.copy(phase = "就绪")
            }
        }
    }

    val dotColor = when (st.phase) {
        "加载中" -> MaterialTheme.colorScheme.tertiary
        "生成中", "思考中" -> MaterialTheme.colorScheme.primary
        "完成"   -> MaterialTheme.colorScheme.secondary
        else     -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset += drag
                }
            },
        shape = if (minimized) CircleShape else RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (minimized) 8.dp else 12.dp,
                end = 4.dp,
                top = 6.dp,
                bottom = 6.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("●", color = dotColor, fontSize = 10.sp)

            if (minimized) {
                if (st.phase == "生成中" || st.phase == "思考中") {
                    Spacer(Modifier.width(4.dp))
                    Text("%.1f".format(st.tokPerSec), fontSize = 11.sp)
                } else if (st.phase == "处理输入") {
                    Spacer(Modifier.width(4.dp))
                    Text("%.1f".format(st.elapsedSec), fontSize = 11.sp)
                }
            } else {
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        append(st.phase)
                        // prefill 阶段显示实时秒数 —— 这就是 TTFT，用来验证前缀缓存省了多少
                        if (st.phase == "处理输入") {
                            append("  ")
                            append("%.1f".format(st.elapsedSec))
                            append("s")
                        }
                        if (st.phase == "生成中" || st.phase == "思考中") {
                            append("  ")
                            append("%.1f".format(st.tokPerSec))
                            append(" tok/s  ")
                            append(st.tokens)
                            append("字")
                        }
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
            }

            Box(
                Modifier
                    .size(24.dp)
                    .clickable { minimized = !minimized },
                contentAlignment = Alignment.Center
            ) {
                Text(if (minimized) "▢" else "－", fontSize = 12.sp)
            }
        }
    }
}
