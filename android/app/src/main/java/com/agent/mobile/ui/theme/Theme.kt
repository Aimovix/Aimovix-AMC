package com.agent.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    secondary = AccentSecondary,
    tertiary = AccentIndigo,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    outline = BorderSubtle,
    outlineVariant = BorderLight,
    onBackground = TextWhite,
    onSurface = TextWhite,
    onSurfaceVariant = TextSecondary,
    error = RedEmergency
)

@Composable
fun AutonomousAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
