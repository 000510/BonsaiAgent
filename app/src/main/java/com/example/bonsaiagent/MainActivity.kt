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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.platform.LocalConfiguration
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

    @OptIn(ExperimentalLayoutApi::class)
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

    // v10.98：历史会话抽屉（左滑 / 点两条杠打开），数据层后续补
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // v10.98：设置 / Agent 切换入口挪到抽屉顶部
    var showSettings by remember { mutableStateOf(false) }
    // v10.99：Agent 输入页内嵌到主界面（不再是全屏弹层）
    var showAgentView by remember { mutableStateOf(false) }
    // v10.99：键盘弹出时禁用抽屉手势（输入时右滑改成收输入法）
    val imeVisible = WindowInsets.isImeVisible
    // v10.98：抽屉加宽，主页被推到只露右侧一小条（约 72dp）
    val drawerWidth = (LocalConfiguration.current.screenWidthDp - 72).dp

    ModalNavigationDrawer(
        drawerState = drawerState,
        // v10.99：键盘弹出时禁用手势，避免输入时右滑误开历史栏
        gesturesEnabled = !imeVisible,
        // v10.99：轻微阴影光效（主页被推右时边缘投影 + 淡化遮罩）
        scrimColor = Color(0x22000000),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(drawerWidth),
                drawerContainerColor = Color.White,
                drawerTonalElevation = 12.dp
            ) {
                // v10.99：设置 / 切换智能体 —— 图标水平排布、小巧
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DrawerIconButton(Icons.Filled.Build, "切换智能体") {
                        scope.launch { drawerState.close() }
                        showAgentView = true
                    }
                    DrawerIconButton(Icons.Filled.Settings, "设置") {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                    // v10.98：左上两条杠（打开历史抽屉）
                    navigationIcon = {
                        if (showAgentView) {
                            // v10.99：Agent 页左上返回对话
                            IconButton(onClick = { showAgentView = false }) {
                                Text("←", fontSize = 22.sp, color = Color(0xFF111111))
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                TwoBarsIcon()
                            }
                        }
                    },
                    title = {},
                    actions = {
                        if (!showAgentView) {
                        // v10.98：新对话 —— 对话框图标，右上角小加号
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
                // v10.99：切换智能体内嵌主界面，不再是全屏弹层
                if (showAgentView) RouterScreen(vm) else ChatScreen(vm)
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

}

// v10.99：抽屉顶部图标按钮（设置 / 切换智能体），小巧、水平排布
@Composable
private fun DrawerIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = label, tint = Color(0xFF111111), modifier = Modifier.size(20.dp))
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
