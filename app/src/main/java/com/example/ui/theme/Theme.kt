package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StarkColorScheme = darkColorScheme(
    primary = StarkCyan,
    secondary = StarkGold,
    tertiary = StarkRed,
    background = StarkDarkBg,
    surface = StarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = StarkCyan,
    onSurface = StarkCyan
)

@Composable
fun StarkHUDTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StarkColorScheme,
        typography = Typography,
        content = content
    )
}

