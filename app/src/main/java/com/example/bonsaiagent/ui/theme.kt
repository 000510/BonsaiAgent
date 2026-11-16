package com.example.bonsaiagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 界面配色：一套种子色推导整套明暗色板。
 *
 * v10.83：原来颜色写死成紫配青（Material 模板色），做得像半成品。
 * 现在给 7 套预设，用户可在设置页随时切换，选择存进 SharedPreferences。
 * 每套只定义「主色 + 强调色」两个种子，其余明暗色阶由 lerp 推导，
 * 这样新增一套配色只要加一行。
 */
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

private fun buildScheme(seed: Color, accent: Color, dark: Boolean) =
    if (dark) darkColorScheme(
        primary = lerp(seed, Color.White, 0.42f),
        onPrimary = lerp(seed, Color.Black, 0.78f),
        primaryContainer = lerp(seed, Color.Black, 0.58f),
        onPrimaryContainer = lerp(seed, Color.White, 0.78f),
        secondary = lerp(accent, Color.White, 0.35f),
        onSecondary = lerp(accent, Color.Black, 0.75f),
        secondaryContainer = lerp(accent, Color.Black, 0.55f),
        onSecondaryContainer = lerp(accent, Color.White, 0.80f),
        tertiary = lerp(seed, Color.White, 0.55f),
        onTertiary = lerp(seed, Color.Black, 0.75f),
        background = lerp(seed, Color.Black, 0.93f),
        onBackground = lerp(seed, Color.White, 0.86f),
        surface = lerp(seed, Color.Black, 0.89f),
        onSurface = lerp(seed, Color.White, 0.86f),
        surfaceVariant = lerp(seed, Color.Black, 0.82f),
        onSurfaceVariant = lerp(seed, Color.White, 0.66f),
        outline = lerp(seed, Color.White, 0.50f),
        outlineVariant = lerp(seed, Color.Black, 0.65f)
    ) else lightColorScheme(
        primary = seed,
        onPrimary = Color.White,
        primaryContainer = lerp(seed, Color.White, 0.82f),
        onPrimaryContainer = lerp(seed, Color.Black, 0.62f),
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = lerp(accent, Color.White, 0.80f),
        onSecondaryContainer = lerp(accent, Color.Black, 0.60f),
        tertiary = lerp(seed, Color.Black, 0.20f),
        onTertiary = Color.White,
        background = lerp(seed, Color.White, 0.965f),
        onBackground = lerp(seed, Color.Black, 0.85f),
        surface = Color.White,
        onSurface = lerp(seed, Color.Black, 0.85f),
        surfaceVariant = lerp(seed, Color.White, 0.90f),
        onSurfaceVariant = lerp(seed, Color.Black, 0.72f),
        outline = lerp(seed, Color.Black, 0.50f),
        outlineVariant = lerp(seed, Color.White, 0.75f)
    )

@Composable
fun BonsaiTheme(
    presetKey: String = "Moss",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val p = presetByKey(presetKey)
    val scheme = remember(p.key, darkTheme) {
        buildScheme(Color(p.seed), Color(p.accent), darkTheme)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
