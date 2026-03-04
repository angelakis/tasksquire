package com.taskwarrior.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF006C4E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF89F8C7),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    error = Color(0xFFB00020)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00487D),
    secondary = Color(0xFF6DDBB4),
    onSecondary = Color(0xFF003828),
    secondaryContainer = Color(0xFF00513A),
    background = Color(0xFF1A1C1E),
    surface = Color(0xFF1A1C1E),
    error = Color(0xFFCF6679)
)

@Composable
fun TaskwarriorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
