package com.fauxx.ui.format

import androidx.annotation.StringRes
import com.fauxx.R
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.EngineState
import com.fauxx.ui.theme.ThemeMode

@StringRes
fun CategoryPool.displayNameRes(): Int = when (this) {
    CategoryPool.MEDICAL -> R.string.category_medical
    CategoryPool.LEGAL -> R.string.category_legal
    CategoryPool.AUTOMOTIVE -> R.string.category_automotive
    CategoryPool.PARENTING -> R.string.category_parenting
    CategoryPool.RETIREMENT -> R.string.category_retirement
    CategoryPool.GAMING -> R.string.category_gaming
    CategoryPool.AGRICULTURE -> R.string.category_agriculture
    CategoryPool.FASHION -> R.string.category_fashion
    CategoryPool.ACADEMIC -> R.string.category_academic
    CategoryPool.REAL_ESTATE -> R.string.category_real_estate
    CategoryPool.COOKING -> R.string.category_cooking
    CategoryPool.SPORTS -> R.string.category_sports
    CategoryPool.FINANCE -> R.string.category_finance
    CategoryPool.TRAVEL -> R.string.category_travel
    CategoryPool.TECHNOLOGY -> R.string.category_technology
    CategoryPool.PETS -> R.string.category_pets
    CategoryPool.HOME_IMPROVEMENT -> R.string.category_home_improvement
    CategoryPool.BEAUTY -> R.string.category_beauty
    CategoryPool.MUSIC -> R.string.category_music
    CategoryPool.FITNESS -> R.string.category_fitness
    CategoryPool.ENTERTAINMENT -> R.string.category_entertainment
    CategoryPool.FOOD -> R.string.category_food
    CategoryPool.POLITICS -> R.string.category_politics
    CategoryPool.SCIENCE -> R.string.category_science
    CategoryPool.BUSINESS -> R.string.category_business
    CategoryPool.OUTDOOR_RECREATION -> R.string.category_outdoor_recreation
    CategoryPool.CRAFTS -> R.string.category_crafts
    CategoryPool.HISTORY -> R.string.category_history
    CategoryPool.ENVIRONMENT -> R.string.category_environment
    CategoryPool.MILITARY_DEFENSE -> R.string.category_military_defense
    CategoryPool.WELLNESS_ALTERNATIVE -> R.string.category_wellness_alternative
    CategoryPool.RELATIONSHIPS_DATING -> R.string.category_relationships_dating
}

@StringRes
fun IntensityLevel.displayNameRes(): Int = when (this) {
    IntensityLevel.LOW -> R.string.intensity_low
    IntensityLevel.MEDIUM -> R.string.intensity_medium
    IntensityLevel.HIGH -> R.string.intensity_high
}

@StringRes
fun ThemeMode.displayNameRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@StringRes
fun EngineState.statusRes(): Int = when (this) {
    EngineState.ACTIVE -> R.string.dashboard_engine_generating
    EngineState.PAUSED_WIFI -> R.string.dashboard_engine_waiting_wifi
    EngineState.PAUSED_BATTERY -> R.string.dashboard_engine_battery_low
    EngineState.PAUSED_RATE_LIMIT -> R.string.dashboard_engine_rate_limit
    EngineState.PAUSED_QUIET_HOURS -> R.string.dashboard_engine_outside_hours
    EngineState.STOPPED -> R.string.dashboard_engine_stopped
}
