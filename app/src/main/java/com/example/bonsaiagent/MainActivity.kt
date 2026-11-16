package com.example.bonsaiagent

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bonsaiagent.ui.BonsaiTheme
import com.example.bonsaiagent.ui.ChatScreen
import com.example.bonsaiagent.ui.RouterScreen
import com.example.bonsaiagent.ui.SettingsScreen
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    // 系统文件选择器（SAF）：无需存储权限，直接选 .gguf
    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { vm.importModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCatcher.mark(applicationContext, "1 onCreate 开始")
        CrashCatcher.install(applicationContext)
        CrashCatcher.mark(applicationContext, "2 崩溃捕获已安装")

        // 主动触发 ViewModel 创建，失败就把堆栈显示出来（而不是黑屏后直接退出）
        val bootError: String? = try {
            CrashCatcher.mark(applicationContext, "3 开始创建 ViewModel")
            vm
            CrashCatcher.mark(applicationContext, "4 ViewModel 创建成功")
            null
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            // ViewModelProvider 会把真因包一层，这里把 cause 链也展开
            var c: Throwable? = t.cause
            var depth = 0
            while (c != null && depth < 5) {
                sw.append("\n----- cause ${++depth} -----\n")
                c.printStackTrace(PrintWriter(sw))
                c = c.cause
            }
            CrashCatcher.mark(applicationContext, "4X ViewModel 创建失败:\n$sw")
            sw.toString()
        }

        if (bootError != null) {
            setContent {
                BonsaiTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "启动失败",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(bootError, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            return
        }

        setContent {
            // 配色跟着设置走，改完立即生效
            BonsaiTheme(presetKey = vm.settings.value.themePreset) {
                MainTabs(vm) {
                    // gguf 没有标准 MIME，用通配让其可选中任意文件，导入时按扩展名处理
                    pickModel.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }
        }
        CrashCatcher.mark(applicationContext, "5 setContent 已调用")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(vm: MainViewModel, onPickModel: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("对话", "路由 / Agent", "设置")
    val ctx = LocalContext.current
    val ver = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    LaunchedEffect(Unit) { CrashCatcher.mark(ctx, "6 首帧已渲染") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BonsaiAgent")
                        if (ver.isNotEmpty()) {
                            Text("v$ver", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    // 新会话：只清对话列表，不用退出 App
                    if (tab != 2) {
                        TextButton(onClick = { vm.newSession() }) { Text("新会话") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(t) },
                        icon = { }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> RouterScreen(vm)
                2 -> SettingsScreen(vm, onPickModel)
            }
        }
    }
}
