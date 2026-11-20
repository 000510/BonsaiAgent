package com.example.bonsaiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.MainViewModel
import com.example.bonsaiagent.llm.ModelLoader
import com.example.bonsaiagent.data.AgentSettings

/**
 * v10.94：设置页重整。
 * - 调试类按钮（NPU/OpenCL 探测、测速）收进「高级 / 调试」折叠组
 * - 长期记忆折叠，默认收起
 * - 投机解码（草稿模型）折叠且标注"实验性"，不外显在主界面
 * - 配色支持自定义（主色/强调色十六进制输入）
 */
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
        SectionTitle("外观")

        // ---------- 配色：预设 + 自定义 ----------
        Text("界面配色", style = MaterialTheme.typography.titleSmall)
        Text(
            "点色块即时切换，选择会保存。深色模式自动适配。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            THEME_PRESETS.forEach { p ->
                val on = s.themePreset == p.key
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(p.seed))
                            .clickable { vm.updateSettings(s.copy(themePreset = p.key)) }
                            .then(
                                if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                else Modifier
                            )
                    )
                    Text(
                        p.label,
                        fontSize = 9.sp,
                        color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 自定义配色：两个颜色输入框 + 预备一个 Custom 色块
        var seedText by remember { mutableStateOf("#%08X".format(s.customSeed.toInt())) }
        var accentText by remember { mutableStateOf("#%08X".format(s.customAccent.toInt())) }
        var colorErr by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = seedText,
                onValueChange = { seedText = it },
                label = { Text("主色") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = accentText,
                onValueChange = { accentText = it },
                label = { Text("强调色") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                val a = parseColorLong(seedText)
                val b = parseColorLong(accentText)
                if (a == null || b == null) {
                    colorErr = "颜色格式应为 #AARRGGBB 或 #RRGGBB"
                } else {
                    colorErr = ""
                    vm.updateSettings(
                        s.copy(themePreset = "Custom", customSeed = a, customAccent = b)
                    )
                    Toast.makeText(ctx, "自定义配色已应用", Toast.LENGTH_SHORT).show()
                }
            }) { Text("应用自定义") }
            if (s.themePreset == "Custom") {
                Text("● 当前：自定义", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (colorErr.isNotBlank()) {
            Text(colorErr, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "格式示例：#FF2E7D58（松针绿）、#FF2C6E9B（深海蓝）。前两位 FF 是不透明度，可省略。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Divider(Modifier.padding(vertical = 4.dp))

        // ---------- 模型 ----------
        SectionTitle("模型")
        Text(vm.modelPath.value ?: "未导入模型", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickModel) { Text("导入本地模型 (.gguf)") }
            OutlinedButton(onClick = vm::loadModel) { Text("加载模型") }
        }

        Divider(Modifier.padding(vertical = 4.dp))
        Text("主模型（${vm.mainModelFiles.size}）", style = MaterialTheme.typography.titleSmall)
        vm.mainModelFiles.forEach { f ->
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
                val isCurrent = vm.modelPath.value == f.absolutePath && vm.modelLoaded.value
                Button(
                    onClick = { vm.loadModelFile(f) },
                    enabled = !isCurrent
                ) {
                    Text(if (isCurrent) "已加载" else "加载")
                }
                TextButton(onClick = { vm.deleteModelFile(f) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (vm.mainModelFiles.isEmpty()) {
            Text("（还没有导入主模型）", style = MaterialTheme.typography.bodySmall)
        }

        SelectionContainer {
            Text(
                vm.status.value,
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.modelLoaded.value) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
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

        // ---------- 推理参数 ----------
        Divider(Modifier.padding(vertical = 8.dp))
        SectionTitle("推理参数")
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

        // ---------- 系统提示词 ----------
        Divider(Modifier.padding(vertical = 8.dp))
        SectionTitle("系统提示词")
        OutlinedTextField(
            value = s.systemPrompt,
            onValueChange = { vm.updateSettings(s.copy(systemPrompt = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            maxLines = 8
        )

        // ---------- 折叠组：长期记忆 ----------
        Divider(Modifier.padding(vertical = 8.dp))
        FoldSection("长期记忆（${vm.memories.size}）") {
            Text(
                "只有明确说了「记住 XX」「以后…」才会记录。每条都能看到来源，随时可删。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (vm.memories.isEmpty()) {
                Text("（还没有记忆）", style = MaterialTheme.typography.bodySmall)
            }
            vm.memories.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("【${f.key}】${f.content}", style = MaterialTheme.typography.bodySmall)
                        if (f.source.isNotBlank()) {
                            Text(
                                "来源：${f.source}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { vm.deleteMemory(f) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---------- 折叠组：实验性功能（投机解码 + 草稿模型 + LiteRT） ----------
        Divider(Modifier.padding(vertical = 8.dp))
        FoldSection("实验性功能") {
            Text(
                "投机解码目前仅对重复/改写类内容有效，通用对话反而变慢，默认不建议使用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("LiteRT 后端（仅 .litertlm 模型）", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NPU", "GPU", "CPU").forEach { be ->
                    val on = s.litertBackend.equals(be, true)
                    if (on) Button(onClick = {}) { Text(be) }
                    else OutlinedButton(onClick = { vm.updateSettings(s.copy(litertBackend = be)) }) { Text(be) }
                }
            }
            Spacer(Modifier.height(6.dp))
            val draftName = s.draftPath.takeIf { it.isNotBlank() }
                ?.let { runCatching { java.io.File(it).name }.getOrNull() } ?: "未选择"
            Text("草稿模型（旧版投机用）：$draftName", style = MaterialTheme.typography.bodySmall)
            if (s.draftPath.isNotBlank()) {
                OutlinedButton(onClick = { vm.setDraftModel(null) }) { Text("取消草稿") }
            }
            vm.draftModelFiles.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteModelFile(f) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---------- 折叠组：高级 / 调试 ----------
        Divider(Modifier.padding(vertical = 8.dp))
        FoldSection("高级 / 调试") {
            OutlinedButton(onClick = { vm.refreshApps() }) {
                Text("刷新应用列表（已识别 ${vm.appCount.value} 个）")
            }
            OutlinedButton(onClick = { vm.probeNpu() }) { Text("探测 NPU 库") }
            OutlinedButton(onClick = { vm.probeNpuDeep() }) { Text("NPU 深度探测") }
            OutlinedButton(onClick = { vm.probeOpenCL() }) { Text("探测 OpenCL") }
            OutlinedButton(onClick = { vm.probeNpuRun() }) { Text("NPU 自检（跑一次推理）") }
            OutlinedButton(onClick = { vm.probeNpuBench() }) { Text("NPU 测速") }
            Spacer(Modifier.height(6.dp))
            Text("下载源（用于参考/记录）", style = MaterialTheme.typography.bodySmall)
            SourceDropdown(
                current = s.downloadSource,
                onSelect = { vm.updateSettings(s.copy(downloadSource = it)) }
            )
        }

        // ---------- 折叠组：Agent 安全 & 云端 ----------
        Divider(Modifier.padding(vertical = 8.dp))
        FoldSection("Agent 安全 & 云端") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许执行危险命令", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = s.allowDangerous,
                    onCheckedChange = { vm.updateSettings(s.copy(allowDangerous = it)) })
            }
            Text(
                "默认关闭。本地模型可能被诱导出 rm -rf、pm uninstall 等命令。建议保持关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = if (s.allowDangerous) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = s.deepseekKey,
                onValueChange = { vm.updateSettings(s.copy(deepseekKey = it)) },
                label = { Text("DeepSeek API Key") },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开启本机 HTTP API", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = s.apiEnabled,
                    onCheckedChange = { vm.updateSettings(s.copy(apiEnabled = it)) })
            }
            Text("监听 127.0.0.1:11434，提供 POST /api/generate。",
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 分组标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp))
}

/** 可折叠分组，默认收起 */
@Composable
private fun FoldSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

/** 解析 #AARRGGBB / #RRGGBB，失败返回 null */
private fun parseColorLong(s: String): Long? {
    val t = s.trim().removePrefix("#")
    return try {
        when (t.length) {
            6 -> 0xFF000000L or t.toLong(16)
            8 -> t.toLong(16)
            else -> null
        }
    } catch (_: Exception) { null }
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
