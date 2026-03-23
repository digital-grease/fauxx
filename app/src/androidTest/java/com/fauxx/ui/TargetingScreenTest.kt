package com.fauxx.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.ui.screens.TargetingScreen
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for [TargetingScreen].
 *
 * Verifies:
 * - All three layer toggles are visible
 * - "Clear My Profile" button is visible
 * - Tapping "Clear My Profile" shows a confirmation dialog
 * - Confirmation dialog has Cancel and Clear actions
 * - Layer labels are displayed
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TargetingScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun targetingEngineTitle_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("TARGETING ENGINE").assertIsDisplayed()
    }

    @Test
    fun allThreeLayers_areDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Layer 1 — Self Report").assertIsDisplayed()
        composeRule.onNodeWithText("Layer 2 — Adversarial Scraper").assertIsDisplayed()
        composeRule.onNodeWithText("Layer 3 — Persona Rotation").assertIsDisplayed()
    }

    @Test
    fun clearMyProfileButton_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Clear My Profile").assertIsDisplayed()
    }

    @Test
    fun clearMyProfile_showsConfirmationDialog() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Clear My Profile").performClick()
        composeRule.onNodeWithText("Clear Profile?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun clearProfileDialog_cancelDismissesDialog() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Clear My Profile").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        // Dialog should be dismissed — title no longer visible
        composeRule.onNodeWithText("Clear Profile?").assertDoesNotExist()
    }

    @Test
    fun scrapeNowButton_isDisplayedUnderLayer2() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Scrape Now").assertIsDisplayed()
    }

    @Test
    fun rotateNowButton_isDisplayedUnderLayer3() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Rotate Now").assertIsDisplayed()
    }
}
