package com.example.bonsaiagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFB388FF),
    background = Color(0xFF0F0F14),
    surface = Color(0xFF1A1A22),
    onBackground = Color(0xFFEDEDF2),
    onSurface = Color(0xFFEDEDF2)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6200EA),
    secondary = Color(0xFF0091EA),
    background = Color(0xFFF6F6FB),
    surface = Color(0xFFFFFFFF)
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
