package com.example.bonsaiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.bonsaiagent.agent.CloudSearch

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

    // v10.142：选壁纸（OpenDocument 拿到的授权重启会失效，所以复制到私有目录再存路径）
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val p = importBackgroundImage(ctx, uri)
            if (p != null) {
                vm.updateSettings(vm.settings.value.copy(bgType = "image", bgImagePath = p))
                Toast.makeText(ctx, "背景已设置", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "这张图读不了，换一张试试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

        // ---------- v10.142 界面背景 ----------
        Divider(Modifier.padding(vertical = 4.dp))
        SectionTitle("界面背景")
        Text(
            "透明玻璃要「折射背后的东西」才看得出玻璃感。纯白背景下就像玻璃盖在白纸上，几乎看不见——" +
            "选一张图片当背景，气泡边缘的折射会立刻明显。",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BG_PRESETS.forEach { c ->
                val sel = s.bgType == "color" && s.bgColor == c
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (sel) 3.dp else 1.dp,
                            color = if (sel) MaterialTheme.colorScheme.primary else Color(0x33000000),
                            shape = CircleShape
                        )
                        .clickable { vm.updateSettings(s.copy(bgType = "color", bgColor = c)) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { bgPicker.launch(arrayOf("image/*")) }) { Text("选一张图片") }
            OutlinedButton(onClick = {
                vm.updateSettings(s.copy(bgType = "none", bgImagePath = ""))
            }) { Text("恢复默认") }
        }
        Text(
            when (s.bgType) {
                "image" -> "当前：自定义图片"
                "color" -> "当前：纯色（纯色下折射也看不太出来，建议用图片）"
                else -> "当前：默认"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
            // v10.94：n-gram 自投机（推荐，零内存开销）
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("n-gram 自投机加速", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "从已输出内容里找重复片段来提速；改写/续写明显变快，闲聊无影响，不占内存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = s.useNgram,
                    onCheckedChange = { vm.updateSettings(s.copy(useNgram = it)) }
                )
            }
            if (s.useNgram) {
                Text("前缀长度 n=${s.ngramN}　每块猜 γ=${s.ngramGamma}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 4, 5).forEach { n ->
                        val on = s.ngramN == n
                        if (on) Button(onClick = {}) { Text("n=$n") }
                        else OutlinedButton(onClick = { vm.updateSettings(s.copy(ngramN = n)) }) { Text("n=$n") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Text(
                "以下是旧版投机解码（需要额外挂一个草稿模型，通用对话反而变慢），一般不用动。",
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

        // v10.137：云端根治 + 中转站管理（可增删改名 + 测连通 + 模型云端拉取）
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("云端根治（模型自判搜索）", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Switch(checked = s.cloudEnabled,
                onCheckedChange = { vm.updateSettings(s.copy(cloudEnabled = it)) })
        }
        Text("开=对话交给云端模型，它自己判断要不要联网搜。",
            style = MaterialTheme.typography.bodySmall)
        CloudProviderSection(vm, s)

        // v10.135：每日云端 Token 上限设置
        Text("每日云端 Token 上限（今日已用 ${s.tokenUsed}）",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = s.tokenBudget.toString(),
            onValueChange = { v ->
                val n = v.filter { it.isDigit() }.toIntOrNull()
                if (n != null && n > 0) vm.updateSettings(s.copy(tokenBudget = n))
            },
            label = { Text("每日上限") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        )
        Text("用超 80% 会提示，超限红色提醒。200 万≈5000 轮/天。",
            style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(24.dp))
    }
}

/** 分组标题 */
@Composable
/** v10.142：背景纯色预设（浅色系，避免深底深字看不清） */
private val BG_PRESETS = listOf(
    0xFFEAF1FF, 0xFFF3E8FF, 0xFFE8F7EF, 0xFFFFF1E6, 0xFFF7F7F9, 0xFFDFE9F5
)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(current: String, onSelect: (String) -> Unit) {
    val opts = CloudSearch.PROVIDERS
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = opts.firstOrNull { it.id == current }?.label ?: current,
            onValueChange = {}, readOnly = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            opts.forEach {
                DropdownMenuItem(text = { Text(it.label) }, onClick = {
                    onSelect(it.id); expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelDropdown(current: String, options: List<String>,
                                  onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = current, onValueChange = {}, readOnly = true,
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


/**
 * v10.137：中转站管理。可增删改名、点选当前站、测连通（顺带从云端拉真实模型列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudProviderSection(vm: MainViewModel, s: AgentSettings) {
    var showAdd by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }

    Text("中转站（可加 / 删 / 改名，「选」设为当前）",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))

    s.providers.forEach { p ->
        val isCur = s.cloudProvider == p.id
        val probe = vm.probeState.value[p.id]
        Column(
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isCur) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    else Color.Transparent
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name + if (isCur) "（当前）" else "",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (!isCur) TextButton(onClick = { vm.selectProvider(p.id) }) { Text("选", fontSize = 12.sp) }
                TextButton(onClick = { renameId = p.id; renameText = p.name }) { Text("改名", fontSize = 12.sp) }
                TextButton(onClick = { vm.probeProvider(p.id) }) { Text("测连通", fontSize = 12.sp) }
                if (s.providers.size > 1)
                    TextButton(onClick = { vm.removeProvider(p.id) }) {
                        Text("删", fontSize = 12.sp, color = Color(0xFFDC2626))
                    }
            }
            Text(p.baseUrl.ifBlank { "（未填地址）" }, fontSize = 11.sp, color = Color(0xFF6B7280))
            if (probe != null) {
                val ok = probe.startsWith("✓")
                val bad = probe.startsWith("✗")
                Text(probe, fontSize = 11.sp,
                    color = if (ok) Color(0xFF16A34A) else if (bad) Color(0xFFDC2626) else Color(0xFF6B7280))
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { newName = ""; newUrl = ""; newKey = ""; showAdd = true },
        modifier = Modifier.fillMaxWidth()) { Text("＋ 添加中转站") }

    val cur = s.providers.firstOrNull { it.id == s.cloudProvider } ?: s.providers.firstOrNull()
    if (cur != null) {
        Text("当前站（${cur.name}）API Key", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(
            value = cur.key,
            onValueChange = { vm.setProviderKey(cur.id, it) },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Text("模型", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        val opts = cur.models.ifEmpty { listOf(cur.defaultModel).filter { it.isNotBlank() } }
        if (opts.isNotEmpty()) {
            ProviderModelDropdown(current = s.cloudModel, options = opts,
                onSelect = { vm.updateSettings(s.copy(cloudModel = it)) })
        }
    }
    Text("「测连通」会拉取该站真实模型列表（GET /models）；云端加了新模型，这里就能选到。",
        style = MaterialTheme.typography.bodySmall)

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加中转站") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                        label = { Text("名称（如：我的站）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newUrl, onValueChange = { newUrl = it },
                        label = { Text("接口地址（…/v1 结尾）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    OutlinedTextField(value = newKey, onValueChange = { newKey = it },
                        label = { Text("API Key") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newUrl.isNotBlank()) { vm.addProvider(newName, newUrl, newKey); showAdd = false }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }

    val rid = renameId
    if (rid != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("重命名中转站") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) { vm.renameProvider(rid, renameText.trim()); renameId = null }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } }
        )
    }
}
