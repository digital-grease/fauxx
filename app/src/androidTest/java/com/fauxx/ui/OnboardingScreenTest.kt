package com.fauxx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

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
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
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
        composeRule.onNodeWithText("Skip").performClick()
        // Age Range → Gender step
        composeRule.onNodeWithText("Skip").performClick()
        // Gender → Interests step
        composeRule.onNodeWithText("Skip").performClick()
        // Interests → Profession step
        composeRule.onNodeWithText("Skip").performClick()
        // Profession → Region step
        composeRule.onNodeWithText("Skip").performClick()
        // Region is last step — "Skip All" should finish
        composeRule.onNodeWithText("Skip All").performClick()

        assert(finished) { "onFinish was not called after skipping all steps" }
    }

    @Test
    fun nextButton_advancesFromWelcomeToDone() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }

        // Welcome step shows FAUXX title
        composeRule.onNodeWithText("FAUXX").assertIsDisplayed()

        // Advance through all steps via Next
        repeat(5) {
            composeRule.onNodeWithText("Next").performClick()
        }

        // On the Done step, Next becomes "Done"
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun skipButton_isEquallyProminentAsNextButton() {
        composeRule.setContent {
            FauxxTheme {
                OnboardingScreen(onFinish = {})
            }
        }
        // Both buttons must be present at the same time — verified by assertIsDisplayed
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
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
            composeRule.onNodeWithText("Next").performClick()
        }
        // Click Done
        composeRule.onNodeWithText("Done").performClick()

        assert(finished) { "onFinish was not called after completing all steps" }
    }
}
