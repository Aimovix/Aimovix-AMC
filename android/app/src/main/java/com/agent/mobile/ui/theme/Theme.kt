package com.agent.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color(0xFF04140D),
    secondary = AccentSecondary,
    tertiary = AccentIndigo,
    background = DarkBackground,
    onBackground = TextWhite,
    surface = DarkSurface,
    onSurface = TextWhite,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondary,
    secondaryContainer = DarkCardElevated,
    onSecondaryContainer = TextWhite,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkCardElevated,
    surfaceContainerHighest = DarkCardElevated,
    outline = BorderLight,
    outlineVariant = BorderSubtle,
    error = RedEmergency
)

/** One corner-radius scale for the whole app. */
val AmcShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AutonomousAgentTheme(content: @Composable () -> Unit) {
    val currentDensity = LocalDensity.current
    val responsiveDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale.coerceIn(0.85f, 1.25f)
    )

    CompositionLocalProvider(LocalDensity provides responsiveDensity) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = Typography,
            shapes = AmcShapes,
            content = content
        )
    }
}
