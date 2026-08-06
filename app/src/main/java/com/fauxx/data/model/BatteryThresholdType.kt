package com.fauxx.data.model

/**
 * Which of the two independent battery thresholds a setting refers to (#216): the level to pause
 * below while charging, or the level to pause below while running on battery.
 */
enum class BatteryThresholdType {
    CHARGING,
    BATTERY
}
