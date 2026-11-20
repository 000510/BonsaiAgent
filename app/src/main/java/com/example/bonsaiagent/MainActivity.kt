package com.example.bonsaiagent

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.bonsaiagent.ui.BonsaiTheme
import com.example.bonsaiagent.ui.ChatScreen
import com.example.bonsaiagent.ui.RouterScreen
import com.example.bonsaiagent.ui.SettingsScreen
import kotlinx.coroutines.launch
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
            BonsaiTheme(
                presetKey = vm.settings.value.themePreset,
                customSeed = vm.settings.value.customSeed,
                customAccent = vm.settings.value.customAccent
            ) {
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
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { CrashCatcher.mark(ctx, "6 首帧已渲染") }
    val scope = rememberCoroutineScope()

    // v10.97：历史会话抽屉（左滑 / 点两条杠打开），数据层后续补
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // v10.97：设置 / Agent 切换改成顶部图标触发的全屏弹层
    var showSettings by remember { mutableStateOf(false) }
    var showRouter by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = Color.White
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "历史对话",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Text(
                    "会话历史记录即将上线，先从这里滑入切换场景",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFFF0F0F2))
                LazyColumnPlaceholder()
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    // v10.97：左上不要标题，换成两条杠（打开历史抽屉）
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            TwoBarsIcon()
                        }
                    },
                    title = {},
                    actions = {
                        // v10.97：新对话 —— 对话框图标，右上角小加号
                        IconButton(onClick = { vm.newSession() }) {
                            Box(Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = "新对话",
                                    tint = Color(0xFF111111)
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(11.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(9.dp),
                                        tint = Color(0xFF111111)
                                    )
                                }
                            }
                        }
                        // v10.97：切换 Agent —— 简约扳手
                        IconButton(onClick = { showRouter = true }) {
                            Icon(
                                Icons.Filled.Build,
                                contentDescription = "切换智能体",
                                tint = Color(0xFF111111)
                            )
                        }
                        // v10.97：设置 —— 简约齿轮
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "设置",
                                tint = Color(0xFF111111)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White,
                        titleContentColor = Color(0xFF111111)
                    )
                )
            },
            bottomBar = {}
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                ChatScreen(vm)
            }
        }
    }

    // v10.97：设置全屏弹层
    if (showSettings) {
        Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                TopAppBar(
                    title = { Text("设置", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.Filled.Add, contentDescription = "关闭",
                                modifier = Modifier.rotate(45f))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(vm, onPickModel)
                }
            }
        }
    }

    // v10.97：Agent 切换全屏弹层
    if (showRouter) {
        Dialog(
            onDismissRequest = { showRouter = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                TopAppBar(
                    title = { Text("切换智能体", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { showRouter = false }) {
                            Icon(Icons.Filled.Add, contentDescription = "关闭",
                                modifier = Modifier.rotate(45f))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )
                Box(Modifier.fillMaxSize()) {
                    RouterScreen(vm)
                }
            }
        }
    }
}

// v10.97：两条杠图标（DeepSeek 式菜单杠），纯 Compose 无需图片
@Composable
private fun TwoBarsIcon() {
    Column(
        Modifier.size(24.dp).padding(vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF111111)))
        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF111111)))
    }
}

// v10.97：历史会话占位列表
@Composable
private fun LazyColumnPlaceholder() {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            "暂无历史会话",
            fontSize = 13.sp,
            color = Color(0xFF9CA3AF),
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            "以后这里会列出你的对话记录，点一下就能回到那次对话",
            fontSize = 12.sp,
            color = Color(0xFFC7CBD1),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
