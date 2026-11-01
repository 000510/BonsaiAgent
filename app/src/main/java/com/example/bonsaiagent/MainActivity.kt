package com.example.bonsaiagent

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bonsaiagent.ui.BonsaiTheme
import com.example.bonsaiagent.ui.ChatScreen
import com.example.bonsaiagent.ui.RouterScreen
import com.example.bonsaiagent.ui.SettingsScreen

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

@Composable
private fun MainTabs(vm: MainViewModel, onPickModel: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("对话", "路由 / Agent", "设置")

    Scaffold(
        topBar = { TopAppBar(title = { Text("BonsaiAgent") }) },
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
