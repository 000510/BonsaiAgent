package com.example.bonsaiagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * 界面配色：一套种子色推导整套明暗色板。
 *
 * v10.83：原来颜色写死成紫配青（Material 模板色），做得像半成品。
 * 现在给 7 套预设，用户可在设置页随时切换，选择存进 SharedPreferences。
 * 每套只定义「主色 + 强调色」两个种子，其余明暗色阶由 lerp 推导，
 * 这样新增一套配色只要加一行。
 *
 * v10.97：用户要求「颜色固定用和 DeepSeek 一样的白底浅蓝按钮」，
 * 因此 BonsaiTheme 改为强制 DeepSeek 浅色板（忽略 presetKey/custom*），
 * 走极简路线：纯白底、品牌蓝主色、浅蓝填充按钮、浅灰半透明输入框。
 * 参数保留以便向后兼容调用处，但不再影响实际配色。
 */
// DeepSeek 品牌蓝
private val DS_BLUE = Color(0xFF4D6BFE)
// 浅蓝填充（功能按钮底 / 选中态）
private val DS_BLUE_LIGHT = Color(0xFFEEF2FF)
// 输入框浅灰
private val DS_GREY = Color(0xFFF5F5F7)

data class ThemePreset(
    val key: String,
    val label: String,
    val seed: Long,
    val accent: Long
)

val THEME_PRESETS = listOf(
    ThemePreset("Moss", "松针绿", 0xFF2E7D58, 0xFFB87B32),
    ThemePreset("Ocean", "深海蓝", 0xFF2C6E9B, 0xFF2E9E9B),
    ThemePreset("Clay", "陶土橙", 0xFFB4622E, 0xFF8C6A2B),
    ThemePreset("Rose", "樱粉", 0xFFB5546A, 0xFF8E5BB0),
    ThemePreset("Violet", "紫罗兰", 0xFF6A5ACD, 0xFFB05ACD),
    ThemePreset("Slate", "石墨灰", 0xFF4A5568, 0xFF2E7D58),
    ThemePreset("Amber", "琥珀黄", 0xFF9C7B1F, 0xFFB4622E)
)

fun presetByKey(key: String): ThemePreset =
    THEME_PRESETS.firstOrNull { it.key == key } ?: THEME_PRESETS[0]

// v10.94：自定义配色（设置页里用滑块选的主色/强调色）
fun customPreset(seed: Long, accent: Long): ThemePreset =
    ThemePreset("Custom", "自定义", seed, accent)

// v10.97：固定 DeepSeek 浅色板，不再按预设推导
private fun deepSeekLight() = lightColorScheme(
    primary = DS_BLUE,
    onPrimary = Color.White,
    primaryContainer = DS_BLUE_LIGHT,
    onPrimaryContainer = Color(0xFF1B2A6B),
    secondary = DS_BLUE,
    onSecondary = Color.White,
    secondaryContainer = DS_BLUE_LIGHT,
    onSecondaryContainer = Color(0xFF1B2A6B),
    tertiary = Color(0xFF1B2A6B),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = DS_GREY,
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB)
)

@Composable
fun BonsaiTheme(
    presetKey: String = "Moss",
    customSeed: Long = 0xFF2E7D58,
    customAccent: Long = 0xFFB87B32,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // v10.97：强制 DeepSeek 浅色，白底浅蓝按钮，忽略 presetKey/customSeed/customAccent/darkTheme
    val scheme = remember { deepSeekLight() }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
