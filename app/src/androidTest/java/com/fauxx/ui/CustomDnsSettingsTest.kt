package com.fauxx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.data.model.DnsMode
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.WebViewCapabilities
import com.fauxx.network.dns.DohPresets
import com.fauxx.ui.screens.SettingsScreen
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The "Custom DNS for Fauxx" card in [SettingsScreen] (#227), driven through the real UI and the
 * real profile repository: the Custom chip's hint, URL validation, removing a stored URL, and the
 * unsaved draft surviving a saved-state round trip (rotation, process death).
 *
 * The card's settings are reset before and after every test, since the repository is the app's
 * real DataStore and would otherwise carry state into later tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CustomDnsSettingsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var profileRepo: PoisonProfileRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        // The card is only interactive on a WebView that can route through a proxy.
        assumeTrue("no PROXY_OVERRIDE on this WebView", WebViewCapabilities.SYSTEM.canUseProxy())
        resetDns()
    }

    @After
    fun tearDown() = resetDns()

    private fun resetDns() = setDns(DnsMode.SYSTEM, DohPresets.DEFAULT_ID, "")

    /**
     * Write the card's settings and wait until the repository's cache reflects them: getProfile()
     * reads a cache refreshed asynchronously from DataStore, and the screen's view model reads it
     * once when created, so a screen built straight after the write could see stale values.
     */
    private fun setDns(mode: DnsMode, provider: String, url: String) {
        runBlocking { profileRepo.updateProfile { it.copy(dnsMode = mode, dohProvider = provider, dohCustomUrl = url) } }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val p = profileRepo.getProfile()
            if (p.dnsMode == mode && p.dohProvider == provider && p.dohCustomUrl == url) return
            Thread.sleep(20)
        }
        error("profile cache never reflected the DNS settings")
    }

    private fun setContent(withSavedUrl: String? = null, enabled: Boolean = true) {
        setDns(
            mode = if (enabled) DnsMode.DOH else DnsMode.SYSTEM,
            provider = if (withSavedUrl != null) DohPresets.CUSTOM_ID else DohPresets.DEFAULT_ID,
            url = withSavedUrl ?: "",
        )
        composeRule.setContent { FauxxTheme { SettingsScreen() } }
    }

    /** Wait for an asynchronous DataStore write to land. */
    private fun awaitProfile(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) { condition() }
    }

    @Test
    fun switchingOn_revealsTheProviders() {
        setContent(enabled = false)
        composeRule.onNodeWithText("Custom DNS for Fauxx").performScrollTo().performClick()
        composeRule.onNodeWithText("Quad9").performScrollTo().assertIsDisplayed()
        awaitProfile { profileRepo.getProfile().dnsMode == DnsMode.DOH }
    }

    @Test
    fun customChipWithoutASavedUrl_explainsInsteadOfDoingNothing() {
        setContent()
        composeRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeRule.onNodeWithText("Save a custom URL below to use it.").performScrollTo().assertIsDisplayed()
        assertEquals(DohPresets.DEFAULT_ID, profileRepo.getProfile().dohProvider)
    }

    @Test
    fun anInvalidUrlIsRejected_andAValidOneIsSavedAndSelected() {
        setContent()
        val field = composeRule.onNodeWithText("Custom DNS-over-HTTPS URL")
        field.performScrollTo().performTextInput("http://dns.example/dns-query")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        composeRule.onNodeWithText("Use a full https:// address").performScrollTo().assertIsDisplayed()

        field.performTextReplacement("https://dns.example/dns-query")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().dohProvider == DohPresets.CUSTOM_ID }
        assertEquals("https://dns.example/dns-query", profileRepo.getProfile().dohCustomUrl)
    }

    @Test
    fun savingABlankField_removesTheStoredUrl() {
        setContent(withSavedUrl = "https://dns.nextdns.io/abc123")
        composeRule.onNodeWithText("Custom DNS-over-HTTPS URL").performScrollTo().performTextClearance()
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().dohCustomUrl.isEmpty() }
        assertEquals(DohPresets.DEFAULT_ID, profileRepo.getProfile().dohProvider)
    }

    @Test
    fun anUnsavedDraftSurvivesASavedStateRoundTrip() {
        setDns(DnsMode.DOH, DohPresets.DEFAULT_ID, "")
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { FauxxTheme { SettingsScreen() } }

        composeRule.onNodeWithText("Custom DNS-over-HTTPS URL").performScrollTo()
            .performTextInput("https://half-typed.example")
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Custom DNS-over-HTTPS URL").performScrollTo()
            .assertTextContains("https://half-typed.example", substring = true)
    }
}
