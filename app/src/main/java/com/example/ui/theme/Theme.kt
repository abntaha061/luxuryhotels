package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF8FF2FF),
    secondary = SkyAccent,
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF004C70),
    onSecondaryContainer = Color(0xFFC7E7FF),
    tertiary = StatusGreen,
    background = DarkNavyBg,
    onBackground = TextLight,
    surface = DarkNavySurface,
    onSurface = TextLight,
    surfaceVariant = DarkNavySurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = ErrorRed,
    onError = Color.White
)

private val LightColorScheme = darkColorScheme( // SubTranslate defaults to cinematic dark
    primary = CyanPrimaryDark,
    onPrimary = Color.White,
    background = DarkNavyBg,
    surface = DarkNavySurface,
    onBackground = TextLight,
    onSurface = TextLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek dark cinematic theme
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
