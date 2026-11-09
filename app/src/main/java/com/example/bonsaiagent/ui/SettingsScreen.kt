package com.example.bonsaiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bonsaiagent.MainViewModel
import com.example.bonsaiagent.llm.ModelLoader
import com.example.bonsaiagent.data.AgentSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onPickModel: () -> Unit) {
    val s = vm.settings.value
    val scroll = rememberScrollState()
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("模型", style = MaterialTheme.typography.titleMedium)
        Text(vm.modelPath.value ?: "未导入模型", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickModel) { Text("导入本地模型 (.gguf)") }
            OutlinedButton(onClick = vm::loadModel) { Text("加载模型") }
        }
        // v10.0：手机里装了哪些应用由系统清单决定，装了新 App 点这里刷新
        OutlinedButton(onClick = { vm.refreshApps() }) {
            Text("刷新应用列表（已识别 ${vm.appCount.value} 个）")
        }
        // v10.23：探测系统里有没有联发科 NPU 运行时库
        OutlinedButton(onClick = { vm.probeNpu() }) {
            Text("探测 NPU 库（查 /vendor/lib64）")
        }
        // v10.26：深度探测 —— 查白名单 + 能不能真的读出来
        OutlinedButton(onClick = { vm.probeNpuDeep() }) {
            Text("NPU 终极探测（扫 system_ext）")
        }
        // v10.31：一键自检 —— 自动用 NPU 后端加载 .litertlm 并生成一句话，
        // 把 LiteRT 真的推到 dispatch → adapter 这条线上
        OutlinedButton(onClick = { vm.probeNpuRun() }) {
            Text("⚡ 一键 NPU 自检（自动跑一次推理）")
        }

        // v10.22：模型管理 —— 私有目录里的模型会一直堆着不删，
        // 实测堆到 11.83 GB（三值模型好几份 + 各家 gguf），把手机撑爆。
        Divider(Modifier.padding(vertical = 4.dp))
        Text("已导入模型（${vm.modelFiles.size}）", style = MaterialTheme.typography.titleSmall)
        vm.modelFiles.forEach { f ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        ModelLoader.sizeText(f.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // v10.25：直接在这里加载/删除，不用再跑文件夹里挑
                TextButton(onClick = { vm.loadModelFile(f) }) {
                    Text("加载")
                }
                TextButton(onClick = { vm.deleteModelFile(f) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (vm.modelFiles.isEmpty()) {
            Text("（还没有导入任何模型）", style = MaterialTheme.typography.bodySmall)
        }
        Divider(Modifier.padding(vertical = 4.dp))

        // v10.20：LiteRT 后端选择（只对 .litertlm 模型有效）
        Text("LiteRT 后端（仅 .litertlm 模型）", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("NPU", "GPU", "CPU").forEach { be ->
                val on = s.litertBackend.equals(be, true)
                if (on) {
                    Button(onClick = {}) { Text(be) }
                } else {
                    OutlinedButton(onClick = { vm.updateSettings(s.copy(litertBackend = be)) }) { Text(be) }
                }
            }
        }
        // 长按可复制：诊断日志太长，复制粘贴比截图清楚得多
        SelectionContainer {
            Text(
                vm.status.value,
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.modelLoaded.value) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }

        // v6.3：一键带走诊断信息。日志几百行，长按拖选太痛苦，
        // 这里直接复制或弹系统分享面板（发给微信/QQ 即可）。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val txt = vm.buildDiagnostics()
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("bonsai-diag", txt))
                Toast.makeText(ctx, "诊断已复制（${txt.length} 字）", Toast.LENGTH_SHORT).show()
            }) { Text("复制诊断") }

            OutlinedButton(onClick = {
                val txt = vm.buildDiagnostics()
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Bonsai Lite 诊断")
                    putExtra(Intent.EXTRA_TEXT, txt)
                }
                ctx.startActivity(Intent.createChooser(i, "发送诊断日志"))
            }) { Text("分享诊断") }
        }

        Divider(Modifier.padding(vertical = 8.dp))
        Text("推理参数", style = MaterialTheme.typography.titleMedium)

        SliderRow("最大输出长度: ${s.maxTokens}", s.maxTokens.toFloat(), 256f, 4096f, 64f) {
            vm.updateSettings(s.copy(maxTokens = it.toInt()))
        }
        SliderRow("温度 Temperature: %.2f".format(s.temperature), s.temperature, 0f, 2f, 0.05f) {
            vm.updateSettings(s.copy(temperature = it))
        }
        SliderRow("Top-K: ${s.topK}", s.topK.toFloat(), 0f, 100f, 1f) {
            vm.updateSettings(s.copy(topK = it.toInt()))
        }
        SliderRow("Top-P: %.2f".format(s.topP), s.topP, 0f, 1f, 0.01f) {
            vm.updateSettings(s.copy(topP = it))
        }
        SliderRow("重复惩罚: %.2f".format(s.repeatPenalty), s.repeatPenalty, 1f, 1.5f, 0.01f) {
            vm.updateSettings(s.copy(repeatPenalty = it))
        }
        SliderRow("上下文窗口: ${s.contextSize}", s.contextSize.toFloat(), 1024f, 16384f, 512f) {
            vm.updateSettings(s.copy(contextSize = it.toInt()))
        }
        SliderRow("推理线程数: ${s.threads}", s.threads.toFloat(), 1f, 12f, 1f) {
            vm.updateSettings(s.copy(threads = it.toInt()))
        }

        Divider(Modifier.padding(vertical = 8.dp))
        Text("系统提示词", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = s.systemPrompt,
            onValueChange = { vm.updateSettings(s.copy(systemPrompt = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            maxLines = 8
        )

        Divider(Modifier.padding(vertical = 8.dp))
        Text("下载源（用于参考/记录）", style = MaterialTheme.typography.titleMedium)
        SourceDropdown(
            current = s.downloadSource,
            onSelect = { vm.updateSettings(s.copy(downloadSource = it)) }
        )

        Divider(Modifier.padding(vertical = 8.dp))
        Text("Agent 安全", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "允许执行危险命令",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = s.allowDangerous,
                onCheckedChange = { vm.updateSettings(s.copy(allowDangerous = it)) }
            )
        }
        Text(
            "默认关闭。本地模型可能被「帮我清理一下存储」这类话诱导出 " +
                    "rm -rf、pm uninstall、su、curl|sh 等命令。" +
                    "关闭时这些操作会被安全墙拦下并告知你；只有你明确打开后才放行。" +
                    "建议保持关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = if (s.allowDangerous) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outline
        )

        Divider(Modifier.padding(vertical = 8.dp))
        Text("云端 API（DeepSeek）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = s.deepseekKey,
            onValueChange = { vm.updateSettings(s.copy(deepseekKey = it)) },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = s.apiBase,
            onValueChange = { vm.updateSettings(s.copy(apiBase = it)) },
            label = { Text("接口地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Divider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开启本机 HTTP API（类似 Ollama）", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Switch(checked = s.apiEnabled, onCheckedChange = {
                vm.updateSettings(s.copy(apiEnabled = it))
            })
        }
        Text("开启后监听 127.0.0.1:11434，提供 POST /api/generate，仅本进程内可访问。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onChanged: (Float) -> Unit
) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = v,
            onValueChange = { v = it; onChanged(it) },
            valueRange = min..max,
            steps = if (step > 0) ((max - min) / step).toInt() - 1 else 0
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceDropdown(current: String, onSelect: (String) -> Unit) {
    val options = listOf("HF镜像", "官方", "ModelScope")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach {
                DropdownMenuItem(text = { Text(it) }, onClick = {
                    onSelect(it); expanded = false
                })
            }
        }
    }
}
