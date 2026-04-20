package com.fauxx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** User-selectable theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Hacker-aesthetic dark theme: deep blacks, green/cyan accents. */
private val FauxxDarkColors = darkColorScheme(
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

/** Light counterpart: same brand accents, darkened so they remain readable on white. */
private val FauxxLightColors = lightColorScheme(
    primary = Color(0xFF007A3D),          // Darkened green for white-bg contrast
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F0CF),
    onPrimaryContainer = Color(0xFF002612),
    secondary = Color(0xFF006B7A),        // Darkened cyan
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8E8F0),
    onSecondaryContainer = Color(0xFF001F26),
    tertiary = Color(0xFFC2410C),         // Deeper warning orange
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF4A4A4A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0)
)

@Composable
fun FauxxTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) FauxxDarkColors else FauxxLightColors,
        content = content
    )
}
