package com.fauxx.ui.format

import androidx.annotation.StringRes
import com.fauxx.R
import com.fauxx.data.model.IntensityLevel
import com.fauxx.ui.theme.ThemeMode

/**
 * Maps Settings-screen enums to localized display strings.
 *
 * Same pattern as [DemographicLabels]: the enum names are the locale-independent
 * on-disk / on-the-wire identity, and the UI resolves display labels through these
 * extensions. The Settings buttons previously rendered `enum.name` directly, which
 * left LOW / MEDIUM / HIGH / SYSTEM / LIGHT / DARK in English regardless of the
 * user's app-language choice — visible as a translation gap when the language picker
 * landed in v0.3.0.
 */

@StringRes
fun IntensityLevel.displayNameRes(): Int = when (this) {
    IntensityLevel.LOW -> R.string.settings_intensity_low
    IntensityLevel.MEDIUM -> R.string.settings_intensity_medium
    IntensityLevel.HIGH -> R.string.settings_intensity_high
}

@StringRes
fun ThemeMode.displayNameRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
