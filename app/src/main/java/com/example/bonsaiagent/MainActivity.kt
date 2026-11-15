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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

        // 未捕获异常落盘，闪退也能拿到堆栈
        CrashCatcher.install(applicationContext)

        // 主动触发 ViewModel 创建：如果它的 init 抛异常，这里能接住并把错误显示出来，
        // 而不是黑屏 0.5 秒直接退出、什么信息都留不下。
        val bootError: String? = try {
            vm
            null
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sw.toString()
        }

        if (bootError != null) {
            setContent {
                BonsaiTheme {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "启动失败",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                bootError,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            return
        }

        setContent {
            BonsaiTheme {
                MainTabs(vm) {
                    // gguf 没有标准 MIME，用通配让其可选中任意文件，导入时按扩展名处理
                    pickModel.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }
        }
    }
}

private data class TabItem(
    val title: String,
    val iconOn: ImageVector,
    val iconOff: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(vm: MainViewModel, onPickModel: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val cs = MaterialTheme.colorScheme
    val loaded = vm.modelLoaded.value

    val tabs = listOf(
        TabItem("对话", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
        TabItem("路由", Icons.Filled.AccountTree, Icons.Outlined.AccountTree),
        TabItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    Scaffold(
        containerColor = cs.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "BonsaiAgent",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            if (loaded) "本地模型已就绪" else "未加载模型",
                            fontSize = 11.sp,
                            color = if (loaded) cs.primary else cs.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 新会话：只清对话列表，不用退出 App（设置页不显示）
                    if (tab != 2) {
                        IconButton(onClick = { vm.newSession() }) {
                            Icon(
                                imageVector = Icons.Outlined.AddComment,
                                contentDescription = "新会话"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = cs.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = cs.surface) {
                tabs.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                imageVector = if (tab == i) item.iconOn else item.iconOff,
                                contentDescription = item.title
                            )
                        },
                        label = { Text(item.title, fontSize = 11.sp) }
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
