package com.fauxx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.ui.screens.DashboardScreen
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for [DashboardScreen].
 *
 * Verifies:
 * - App title is shown
 * - Protection toggle is displayed and starts in inactive state
 * - Action counter labels are visible
 * - Noise ratio card is displayed
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun appTitle_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                DashboardScreen()
            }
        }
        composeRule.onNodeWithText("FAUXX").assertIsDisplayed()
    }

    @Test
    fun protectionStatus_showsInactiveByDefault() {
        composeRule.setContent {
            FauxxTheme {
                DashboardScreen()
            }
        }
        // Default state is disabled — the protection card shows "INACTIVE"
        composeRule.onNodeWithText("INACTIVE").assertIsDisplayed()
        composeRule.onNodeWithText("Engine stopped").assertIsDisplayed()
    }

    @Test
    fun actionCounters_areDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                DashboardScreen()
            }
        }
        composeRule.onNodeWithText("TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("THIS WEEK").assertIsDisplayed()
    }

    @Test
    fun noiseRatioCard_isDisplayed() {
        composeRule.setContent {
            FauxxTheme {
                DashboardScreen()
            }
        }
        composeRule.onNodeWithText("NOISE RATIO").assertIsDisplayed()
    }

    // toggleProtectionOn_changesStatusToActive removed 2026-05-13: it claimed to
    // test the off→on transition but its body asserted "INACTIVE" twice without
    // ever invoking the toggle. The remaining tests in this file cover the
    // structural rendering. A real toggle test is deferred to the Phase 2 emulator
    // smoke suite — it needs FOREGROUND_SERVICE permission handling and a
    // foreground-service-startable context that instrumented unit tests don't
    // reliably provide. See .devloop/spikes/integration-testing-audit.md.
}
