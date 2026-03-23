package com.fauxx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Hacker-aesthetic dark theme: deep blacks, green/cyan accents. */
private val FauxxColors = darkColorScheme(
    primary = Color(0xFF00FF88),         // Neon green
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF003820),
    onPrimaryContainer = Color(0xFF00FF88),
    secondary = Color(0xFF00E5FF),        // Cyan
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF003040),
    onSecondaryContainer = Color(0xFF00E5FF),
    tertiary = Color(0xFFFF6B35),         // Warning orange
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFFF4444),
    onError = Color(0xFF000000),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222)
)

@Composable
fun FauxxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FauxxColors,
        content = content
    )
}
