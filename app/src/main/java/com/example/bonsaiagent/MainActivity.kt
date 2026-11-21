package com.example.bonsaiagent

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MainTabs(vm: MainViewModel, onPickModel: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { CrashCatcher.mark(ctx, "6 首帧已渲染") }
    val scope = rememberCoroutineScope()

    // v10.100：自定义抽屉 —— 主页面向右推出（非覆盖式），键盘弹出时右滑收输入法
    var showSettings by remember { mutableStateOf(false) }
    // v10.100：Agent 输入页内嵌到主界面（顶栏保持主页面原样，内容区切换）
    var showAgentView by remember { mutableStateOf(false) }
    // v10.99：键盘弹出时右滑改成收输入法
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    // v10.98：抽屉加宽，主页被推到只露右侧一小条（约 72dp）
    val drawerWidth = (LocalConfiguration.current.screenWidthDp - 72).dp
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    // v10.100：抽屉滑动偏移，0=关 / drawerWidthPx=开
    val drawerOffset = remember { Animatable(0f) }
    fun openDrawer() { scope.launch { drawerOffset.animateTo(drawerWidthPx) } }
    fun closeDrawer() { scope.launch { drawerOffset.animateTo(0f) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)   // v10.102：根容器白底，主内容缩小时不再露出黑边
            // v10.102：手势改 draggable —— 支持 fling 惯性（快划即开/收）；键盘弹出右滑收输入法
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    if (!imeVisible) {
                        val new = (drawerOffset.value + delta).coerceIn(0f, drawerWidthPx)
                        scope.launch { drawerOffset.snapTo(new) }
                    }
                },
                onDragStopped = { velocity ->
                    if (imeVisible) {
                        // 键盘弹出：右滑（正速度）收输入法
                        if (velocity > 300f) focusManager.clearFocus()
                    } else {
                        // v10.102：惯性 —— 快划直接展开/收回（±600px/s），慢划看距离（30%）
                        val open = when {
                            velocity > 600f -> true
                            velocity < -600f -> false
                            else -> drawerOffset.value > drawerWidthPx * 0.3f
                        }
                        val target = if (open) drawerWidthPx else 0f
                        scope.launch { drawerOffset.animateTo(target) }
                    }
                }
            )
    ) {
        // ---- 抽屉层（最底层）：关闭时整体移到屏幕左侧外 ----
        Box(
            Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .offset { IntOffset((drawerOffset.value - drawerWidthPx).toInt(), 0) }
        ) {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxSize(),
                // v10.103: 侧栏近白 #FAFAFA（DeepSeek 侧栏色），去 tonal tint 消除浅紫
                drawerContainerColor = Color(0xFFFAFAFA),
                drawerTonalElevation = 0.dp
            ) {
                // v10.99：设置 / 切换智能体 —— 图标水平排布、小巧
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DrawerIconButton(Icons.Filled.Build, "切换智能体") {
                        closeDrawer()
                        showAgentView = true
                    }
                    DrawerIconButton(Icons.Filled.Settings, "设置") {
                        closeDrawer()
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
            // v10.103: 抽屉打开过程由深(30%)变浅(0)，完全打开后遮罩消失
            Box(
                Modifier.fillMaxSize().background(
                    Color.Black.copy(alpha = 0.3f * (1f - (if (drawerWidthPx > 0f) drawerOffset.value / drawerWidthPx else 0f)))
                )
            )
        }

        // ---- 主内容层（上层）：向右推出，左边缘投影 ----
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(drawerOffset.value.toInt(), 0) }
                // v10.101：抽屉打开时主页面轻微缩小（DeepSeek 式立体感）
                // v10.104: shadow 挪到 graphicsLayer 之前，主页面 alpha 不再冲淡阴影/缩小轮廓
                .shadow(16.dp, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp), clip = false)
                .graphicsLayer {
                    val p = if (drawerWidthPx > 0f) drawerOffset.value / drawerWidthPx else 0f
                    val s = 1f - 0.04f * p
                    scaleX = s; scaleY = s
                    // v10.103: 主页面全色 -> 80% 透明，透出底层抽屉、分清主次
                    alpha = 1f - 0.2f * p
                }
                // v10.101：抽屉打开时点主页面任意处收回（indication=null 无水波）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (drawerOffset.value > 10f) closeDrawer() }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        // v10.100：顶栏保持主页面原样（两条杠 + 新对话），Agent 页不变样
                        navigationIcon = {
                            IconButton(onClick = { openDrawer() }) { TwoBarsIcon() }
                        },
                        title = {},
                        actions = {
                            IconButton(onClick = { vm.newSession(); showAgentView = false }) {
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
                    // v10.100：Agent 输入页内嵌主界面（顶栏不变），顶部带返回对话入口
                    if (showAgentView) RouterScreen(vm, onBackToChat = { showAgentView = false })
                    else ChatScreen(vm)
                }
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
