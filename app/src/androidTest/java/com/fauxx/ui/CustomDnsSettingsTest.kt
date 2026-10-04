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
 * real profile repository: the Custom chip's hint, URL validation, removing a stored URL, the
 * unsaved draft surviving a saved-state round trip (rotation, process death), the plain-DNS
 * server path, and the DNS-noise toggle.
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

    private fun resetDns() {
        setDns(DnsMode.SYSTEM, DohPresets.DEFAULT_ID, "")
        runBlocking { profileRepo.updateProfile { it.copy(plainDnsServer = "", routeDnsNoise = false) } }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val p = profileRepo.getProfile()
            if (p.plainDnsServer.isEmpty() && !p.routeDnsNoise) return
            Thread.sleep(20)
        }
    }

    /**
     * Write the card's settings and wait until the repository's cache reflects them: getProfile()
     * reads a cache refreshed asynchronously from DataStore, and the screen's view model reads it
     * once when created, so a screen built straight after the write could see stale values.
     */
    private fun setDns(mode: DnsMode, provider: String, url: String) {
        runBlocking {
            profileRepo.updateProfile {
                it.copy(
                    dnsMode = mode, dohProvider = provider, dohCustomUrl = url,
                    dohCustomServerIp = "", dohSkipCertificateCheck = false,
                )
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val p = profileRepo.getProfile()
            if (p.dnsMode == mode && p.dohProvider == provider && p.dohCustomUrl == url &&
                p.dohCustomServerIp.isEmpty() && !p.dohSkipCertificateCheck
            ) return
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
    fun aServerIpIsValidatedAndSavedWithTheUrl() {
        setContent()
        composeRule.onNodeWithText("Custom DNS-over-HTTPS URL").performScrollTo().performTextInput("https://dns.lan/dns-query")
        val ip = composeRule.onNodeWithText("Server IP (optional)")
        ip.performScrollTo().performTextInput("dns.lan")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        composeRule.onNodeWithText("Enter an IP address, like 192.168.1.2").performScrollTo().assertIsDisplayed()
        assertEquals("", profileRepo.getProfile().dohCustomUrl)

        ip.performTextReplacement("192.168.6.7")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().dohCustomServerIp == "192.168.6.7" }
        assertEquals("https://dns.lan/dns-query", profileRepo.getProfile().dohCustomUrl)
    }

    @Test
    fun theCertificateSwitchAppearsOnlyForACustomUrl_andTogglesIt() {
        setContent()
        composeRule.onNodeWithText("Skip certificate check").assertDoesNotExist()

        composeRule.onNodeWithText("Custom DNS-over-HTTPS URL").performScrollTo().performTextInput("https://dns.lan/dns-query")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().dohProvider == DohPresets.CUSTOM_ID }

        composeRule.onNodeWithText("Skip certificate check").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().dohSkipCertificateCheck }
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

    @Test
    fun plainChipWithoutASavedServer_explainsInsteadOfSwitching() {
        setContent()
        composeRule.onNodeWithText("Unencrypted DNS server").performScrollTo().performClick()
        composeRule.onNodeWithText("Save a server address below to use it.").performScrollTo().assertIsDisplayed()
        assertEquals(DnsMode.DOH, profileRepo.getProfile().dnsMode)
    }

    @Test
    fun aHostnameIsRejected_andAnIpIsSavedAndSwitchesToPlain() {
        setContent()
        composeRule.onNodeWithText("Unencrypted DNS server").performScrollTo().performClick()
        val field = composeRule.onNodeWithText("DNS server IP address")
        field.performScrollTo().performTextInput("dns.example")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        composeRule.onNodeWithText("Use an IP address, optionally with :port").performScrollTo().assertIsDisplayed()

        field.performTextReplacement("9.9.9.9")
        composeRule.onNodeWithText("Save and use").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().dnsMode == DnsMode.PLAIN }
        assertEquals("9.9.9.9", profileRepo.getProfile().plainDnsServer)
    }

    @Test
    fun theDnsNoiseToggleIsStored() {
        setContent()
        composeRule.onNodeWithText("Also use it for DNS noise").performScrollTo().performClick()
        awaitProfile { profileRepo.getProfile().routeDnsNoise }
    }

    @Test
    fun tappingDohAfterAnUnsavedPlainAttempt_returnsToTheDohSection() {
        setContent()
        composeRule.onNodeWithText("Unencrypted DNS server").performScrollTo().performClick()
        composeRule.onNodeWithText("DNS server IP address").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Encrypted (DNS-over-HTTPS)").performScrollTo().performClick()
        composeRule.onNodeWithText("Quad9").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("DNS server IP address").assertDoesNotExist()
    }
}
