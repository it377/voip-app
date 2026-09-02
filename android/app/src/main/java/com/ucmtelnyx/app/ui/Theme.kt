package com.ucmtelnyx.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matches the web app's dark palette (public/styles.css :root).
val AppBackground = Color(0xFF0F1115)
val AppPanel = Color(0xFF171A21)
val AppPanel2 = Color(0xFF1F232C)
val AppBorder = Color(0xFF2A2F3A)
val AppText = Color(0xFFE8EAED)
val AppTextDim = Color(0xFF9AA1AC)
val AppAccent = Color(0xFF4F8CFF)
val AppAccent2 = Color(0xFF2ECC71)
val AppDanger = Color(0xFFFF5B5B)

private val DarkColors = darkColorScheme(
    background = AppBackground,
    surface = AppPanel,
    primary = AppAccent,
    secondary = AppAccent2,
    error = AppDanger,
    onBackground = AppText,
    onSurface = AppText,
)

@Composable
fun UcmTelnyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
