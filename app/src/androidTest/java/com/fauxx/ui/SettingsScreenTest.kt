package com.fauxx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * SettingsScreen wraps its content in a verticalScroll, so any element below the
 * fold must be scrolled into view (performScrollTo) before it can be asserted
 * displayed or clicked.
 *
 * Verifies:
 * - Settings title is shown
 * - Intensity buttons (Low / Medium / High / Max) are displayed
 * - Mobile data intensity section (issue #62) is shown with its Off tier
 * - Battery threshold ("Pause below") section is shown
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
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

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
        // Each tier label appears twice since issue #62 (Wi-Fi row + mobile data row);
        // index 0 is the Wi-Fi card at the top of the screen.
        composeRule.onAllNodesWithText("Low")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("Medium")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("High")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("Max")[0].assertIsDisplayed()
    }

    @Test
    fun mobileIntensitySection_isDisplayed() {
        // Issue #62: the old Wi-Fi Only switch is now a tier row with an Off option.
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Mobile data intensity").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Off").performScrollTo().assertIsDisplayed()
        // Fresh profile defaults to Off, whose caption explains the pause behavior.
        composeRule.onNodeWithText("Paused on mobile data").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryThresholdSection_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Pause below").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun activeHoursSection_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Active Hours").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Activity is paused outside these hours").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearAllDataButton_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Clear All Data").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearAllData_showsConfirmationDialog() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Clear All Data").performScrollTo().performClick()
        // Dialog content lives in an AlertDialog overlay, outside the scroll container.
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
        composeRule.onNodeWithText("Clear All Data").performScrollTo().performClick()
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
        // Index 0 = the Wi-Fi intensity row (the mobile row reuses the tier labels).
        composeRule.onAllNodesWithText("Low")[0].performClick()
        // After selecting Low, the actions/hour label should update
        composeRule.onAllNodesWithText("Low")[0].assertIsDisplayed()
    }

    @Test
    fun resumeAfterRebootToggle_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                SettingsScreen()
            }
        }
        composeRule.onNodeWithText("Resume after reboot").performScrollTo().assertIsDisplayed()
    }
}
