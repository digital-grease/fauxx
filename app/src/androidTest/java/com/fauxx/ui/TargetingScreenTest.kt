package com.fauxx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 *
 * Note: [TargetingScreen]'s root Column uses `.verticalScroll(...)`, so any element
 * below the first card (Layer 2/3, "Rotate Now", "Clear My Profile") must be brought
 * into the viewport with `performScrollTo()` before asserting visibility or clicking.
 * The confirmation dialog renders in an AlertDialog overlay (outside the scroll
 * container) so its nodes do not need scrolling.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TargetingScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

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
        // Title is the first element at the top of the screen — no scroll needed.
        composeRule.onNodeWithText("TARGETING ENGINE").assertIsDisplayed()
    }

    @Test
    fun allThreeLayers_areDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        // Layer 1 card sits directly under the title (top of screen).
        composeRule.onNodeWithText("Layer 1 — Self Report").assertIsDisplayed()
        // Layers 2 and 3 are below the fold in the verticalScroll container.
        composeRule.onNodeWithText("Layer 2 — Ad Profile Import").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Layer 3 — Persona Rotation").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearMyProfileButton_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        // "Clear My Profile" is the last element in the scroll container.
        composeRule.onNodeWithText("Clear My Profile").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearMyProfile_showsConfirmationDialog() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        composeRule.onNodeWithText("Clear My Profile").performScrollTo().performClick()
        // Dialog is an AlertDialog overlay, not inside the scroll container.
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
        composeRule.onNodeWithText("Clear My Profile").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        // Dialog should be dismissed — title no longer visible
        composeRule.onNodeWithText("Clear Profile?").assertDoesNotExist()
    }

    @Test
    fun rotateNowButton_isDisplayedUnderLayer3() {
        composeRule.setContent {
            FauxxTheme {
                TargetingScreen()
            }
        }
        // "Rotate Now" renders under the Layer 3 card (enabled by default) — below the fold.
        composeRule.onNodeWithText("Rotate Now").performScrollTo().assertIsDisplayed()
    }
}
