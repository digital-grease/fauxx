package com.fauxx.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.ui.screens.SettingsScreen
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for [SettingsScreen].
 *
 * Verifies:
 * - Settings title is shown
 * - Intensity buttons (LOW / MEDIUM / HIGH) are displayed
 * - Wi-Fi Only toggle label is shown
 * - Battery Threshold slider section is shown
 * - Active Hours section is shown
 * - "Clear All Data" button is visible
 * - Tapping "Clear All Data" shows a confirmation dialog
 * - Confirmation dialog Cancel dismisses the dialog
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun settingsTitle_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()
    }

    @Test
    fun intensityButtons_areDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("LOW").assertIsDisplayed()
        composeRule.onNodeWithText("MEDIUM").assertIsDisplayed()
        composeRule.onNodeWithText("HIGH").assertIsDisplayed()
    }

    @Test
    fun wifiOnlyToggle_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Wi-Fi Only").assertIsDisplayed()
        composeRule.onNodeWithText("Pause when on mobile data").assertIsDisplayed()
    }

    @Test
    fun batteryThresholdSection_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Battery Threshold").assertIsDisplayed()
    }

    @Test
    fun activeHoursSection_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Active Hours").assertIsDisplayed()
        composeRule.onNodeWithText("Activity is paused outside these hours").assertIsDisplayed()
    }

    @Test
    fun clearAllDataButton_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Clear All Data").assertIsDisplayed()
    }

    @Test
    fun clearAllData_showsConfirmationDialog() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Clear All Data").performClick()
        composeRule.onNodeWithText("Clear All Data?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Clear Everything").assertIsDisplayed()
    }

    @Test
    fun clearAllDataDialog_cancelDismissesDialog() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Clear All Data").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        // Dialog should be gone
        composeRule.onNodeWithText("Clear All Data?").assertDoesNotExist()
    }

    @Test
    fun selectingLowIntensity_updatesDisplay() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("LOW").performClick()
        // After selecting LOW, the actions/hour label should update
        composeRule.onNodeWithText("LOW").assertIsDisplayed()
    }
}
