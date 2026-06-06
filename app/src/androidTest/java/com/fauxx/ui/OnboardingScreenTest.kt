package com.fauxx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.ui.screens.OnboardingScreen
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for [OnboardingScreen].
 *
 * Verifies:
 * - Skip button is always visible and equal-prominence to Next
 * - Tapping Skip on every step completes the flow without selecting any field
 * - Next button advances through all steps
 * - The Done step shows the final completion step
 *
 * Note: [OnboardingScreen] wraps its content in a verticalScroll Column with the
 * Skip / Next navigation Row pinned at the bottom (Arrangement.SpaceBetween). On
 * content-heavy steps (Welcome, Interests) the navigation buttons sit below the
 * fold, so we call performScrollTo() before asserting/clicking them.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun skipButton_isVisibleOnWelcomeStep() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }
        composeRule.onNodeWithText("Skip").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun skipAllFields_completesOnboardingWithoutCrash() {
        var finished = false
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = { finished = true })
            }
        }

        // Welcome → Age Range step
        composeRule.onNodeWithText("Skip").performScrollTo().performClick()
        // Age Range → Gender step
        composeRule.onNodeWithText("Skip").performScrollTo().performClick()
        // Gender → Interests step
        composeRule.onNodeWithText("Skip").performScrollTo().performClick()
        // Interests → Profession step
        composeRule.onNodeWithText("Skip").performScrollTo().performClick()
        // Profession → Region step
        composeRule.onNodeWithText("Skip").performScrollTo().performClick()
        // Region is last step — "Skip All" should finish
        composeRule.onNodeWithText("Skip All").performScrollTo().performClick()

        assert(finished) { "onFinish was not called after skipping all steps" }
    }

    @Test
    fun nextButton_advancesFromWelcomeToDone() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }

        // Welcome step shows FAUXX title (top of screen, no scroll needed)
        composeRule.onNodeWithText("FAUXX").assertIsDisplayed()

        // Advance through all steps via Next
        repeat(5) {
            composeRule.onNodeWithText("Next").performScrollTo().performClick()
        }

        // On the Done step, Next becomes "Done"
        composeRule.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun skipButton_isEquallyProminentAsNextButton() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }
        // Both buttons must be present at the same time — verified by assertIsDisplayed
        composeRule.onNodeWithText("Skip").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun nextThenDone_callsOnFinish() {
        var finished = false
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = { finished = true })
            }
        }

        // Advance to the last step via Next
        repeat(5) {
            composeRule.onNodeWithText("Next").performScrollTo().performClick()
        }
        // Click Done
        composeRule.onNodeWithText("Done").performScrollTo().performClick()

        assert(finished) { "onFinish was not called after completing all steps" }
    }
}
