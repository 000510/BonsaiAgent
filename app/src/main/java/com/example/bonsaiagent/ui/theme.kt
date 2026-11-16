package com.example.bonsaiagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Bonsai 配色：松针绿（主）+ 陶土橙（强调）+ 米白/墨绿（底）。
 *
 * v10.82：只换颜色值，不动布局。原来的紫配青是 Material 默认模板色，
 * 放在一个叫「盆栽」的 App 上很跳，换成偏自然的低饱和色，长时间看不刺眼。
 */
private val Moss = Color(0xFF2E7D58)        // 松针绿
private val MossDark = Color(0xFF14452E)
private val MossBright = Color(0xFF7ED0A6)  // 深色模式主色（提亮保证对比度）
private val Clay = Color(0xFFB87B32)        // 陶土
private val ClayBright = Color(0xFFE8B96A)

private val DarkColors = darkColorScheme(
    primary = MossBright,
    onPrimary = Color(0xFF00391F),
    primaryContainer = MossDark,
    onPrimaryContainer = Color(0xFFA8EBC8),
    secondary = ClayBright,
    onSecondary = Color(0xFF3E2500),
    secondaryContainer = Color(0xFF54391B),
    onSecondaryContainer = Color(0xFFF3DCBB),
    tertiary = Color(0xFF8FC8B0),
    onTertiary = Color(0xFF00382A),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE3E6DC),
    surface = Color(0xFF181C16),
    onSurface = Color(0xFFE3E6DC),
    surfaceVariant = Color(0xFF232821),
    onSurfaceVariant = Color(0xFFC2C9BC),
    outline = Color(0xFF8C9384),
    outlineVariant = Color(0xFF3A4034)
)

private val LightColors = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7EFD9),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Clay,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E3C6),
    onSecondaryContainer = Color(0xFF2C1A00),
    tertiary = Color(0xFF4F7A66),
    onTertiary = Color.White,
    background = Color(0xFFFAF8F2),
    onBackground = Color(0xFF1B1E19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1E19),
    surfaceVariant = Color(0xFFEFEDE4),
    onSurfaceVariant = Color(0xFF46493F),
    outline = Color(0xFF77796C),
    outlineVariant = Color(0xFFD6D4CA)
)

@Composable
fun BonsaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
