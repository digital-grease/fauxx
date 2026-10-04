package com.fauxx

import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.MIN_ACTIVE_SEARCH_ENGINES
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.modules.SEARCH_ENGINE_IDS
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.scheduling.CircadianObserver
import com.fauxx.locale.LocaleManager
import com.fauxx.logging.EncryptedFileTree
import com.fauxx.support.MainDispatcherRule
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.ui.viewmodels.SettingsViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Settings "delete all locally-stored data" control must clear the ENTIRE recoverable
 * trail — not just the DAOs, but the encrypted log files (up to 48h of query/persona text)
 * and the Markov model trained from custom interests. Otherwise a user who erases data
 * leaves a recoverable activity trail on a device that may later be seized or handed over.
 *
 * (TargetingViewModel.clearProfile applies the identical clearLogs() + clearAllState() pair;
 * it is best verified instrumented in Phase 4 because its init collects context.fauxxDataStore,
 * which a pure-JVM test can't supply cleanly.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile() } returns PoisonProfile()
    }
    private val actionLogDao: ActionLogDao = mockk(relaxed = true)
    private val demographicDao: DemographicProfileDao = mockk(relaxed = true)
    private val platformDao: PlatformProfileDao = mockk(relaxed = true)
    private val personaHistoryDao: PersonaHistoryDao = mockk(relaxed = true)
    private val profileSnapshotDao: ProfileSnapshotDao = mockk(relaxed = true)
    private val targetingEngine: TargetingEngine = mockk(relaxed = true)
    private val encryptedFileTree: EncryptedFileTree = mockk(relaxed = true)
    private val localeManager: LocaleManager = mockk(relaxed = true) {
        every { userOverrideFlow } returns MutableStateFlow(null)
    }
    private val markovGenerator: MarkovQueryGenerator = mockk(relaxed = true)
    private val circadianObserver: CircadianObserver = mockk(relaxed = true)

    private fun viewModel() = SettingsViewModel(
        profileRepo, actionLogDao, demographicDao, platformDao, personaHistoryDao,
        profileSnapshotDao, targetingEngine, encryptedFileTree, localeManager,
        markovGenerator, circadianObserver
    )

    // --- #227 custom DNS ---

    @Test
    fun `the custom DNS switch maps to DoH and back to the system resolver`() = runTest {
        val vm = viewModel()
        vm.setCustomDnsEnabled(true)
        assertEquals(com.fauxx.data.model.DnsMode.DOH, vm.uiState.value.dnsMode)
        vm.setCustomDnsEnabled(false)
        assertEquals(com.fauxx.data.model.DnsMode.SYSTEM, vm.uiState.value.dnsMode)
    }

    @Test
    fun `a custom DoH URL is saved and selected only when it is https`() = runTest {
        val vm = viewModel()
        assertEquals(com.fauxx.ui.viewmodels.DohSaveResult.INVALID_URL, vm.saveDohCustom("http://dns.example/dns-query", ""))
        assertEquals(com.fauxx.network.dns.DohPresets.DEFAULT_ID, vm.uiState.value.dohProvider)

        assertEquals(com.fauxx.ui.viewmodels.DohSaveResult.SAVED, vm.saveDohCustom("  https://dns.nextdns.io/abc123 ", ""))
        assertEquals(com.fauxx.network.dns.DohPresets.CUSTOM_ID, vm.uiState.value.dohProvider)
        assertEquals("https://dns.nextdns.io/abc123", vm.uiState.value.dohCustomUrl)
    }

    @Test
    fun `saving a blank URL removes the stored one and leaves the custom provider`() = runTest {
        val vm = viewModel()
        vm.saveDohCustom("https://dns.nextdns.io/abc123", "")
        assertEquals(com.fauxx.ui.viewmodels.DohSaveResult.SAVED, vm.saveDohCustom("   ", ""))
        assertEquals("", vm.uiState.value.dohCustomUrl)
        assertEquals(com.fauxx.network.dns.DohPresets.DEFAULT_ID, vm.uiState.value.dohProvider)
    }

    @Test
    fun `a custom URL is saved with its server IP, and a bad IP saves nothing`() = runTest {
        val vm = viewModel()
        assertEquals(com.fauxx.ui.viewmodels.DohSaveResult.INVALID_SERVER_IP, vm.saveDohCustom("https://dns.lan/dns-query", "dns.lan"))
        assertEquals("", vm.uiState.value.dohCustomUrl)

        assertEquals(com.fauxx.ui.viewmodels.DohSaveResult.SAVED, vm.saveDohCustom("https://dns.lan/dns-query", " 192.168.6.7 "))
        assertEquals("192.168.6.7", vm.uiState.value.dohCustomServerIp)
        assertEquals(com.fauxx.network.dns.DohPresets.CUSTOM_ID, vm.uiState.value.dohProvider)
    }

    @Test
    fun `removing the custom URL also removes its server IP and certificate opt-out`() = runTest {
        val vm = viewModel()
        vm.saveDohCustom("https://dns.lan/dns-query", "192.168.6.7")
        vm.setDohSkipCertificateCheck(true)
        assertTrue(vm.uiState.value.dohSkipCertificateCheck)

        vm.saveDohCustom("", "192.168.6.7")
        assertEquals("", vm.uiState.value.dohCustomServerIp)
        assertFalse("a later URL must not inherit the opt-out", vm.uiState.value.dohSkipCertificateCheck)
    }

    @Test
    fun `plain DNS cannot be selected before a server is saved`() = runTest {
        val vm = viewModel()
        vm.setCustomDnsEnabled(true)
        assertFalse(vm.setDnsMode(com.fauxx.data.model.DnsMode.PLAIN))
        assertEquals(com.fauxx.data.model.DnsMode.DOH, vm.uiState.value.dnsMode)
    }

    @Test
    fun `saving a plain server validates it, selects it, and a blank save removes it`() = runTest {
        val vm = viewModel()
        vm.setCustomDnsEnabled(true)
        assertFalse(vm.savePlainDnsServer("dns.example"))
        assertTrue(vm.savePlainDnsServer(" 9.9.9.9:5353 "))
        assertEquals("9.9.9.9:5353", vm.uiState.value.plainDnsServer)
        assertEquals(com.fauxx.data.model.DnsMode.PLAIN, vm.uiState.value.dnsMode)

        assertTrue(vm.savePlainDnsServer(""))
        assertEquals("", vm.uiState.value.plainDnsServer)
        assertEquals("falls back to encrypted DNS", com.fauxx.data.model.DnsMode.DOH, vm.uiState.value.dnsMode)
    }

    @Test
    fun `switching custom DNS off and on restores plain mode`() = runTest {
        val vm = viewModel()
        vm.setCustomDnsEnabled(true)
        vm.savePlainDnsServer("9.9.9.9")
        vm.setCustomDnsEnabled(false)
        assertEquals(com.fauxx.data.model.DnsMode.SYSTEM, vm.uiState.value.dnsMode)
        vm.setCustomDnsEnabled(true)
        assertEquals(com.fauxx.data.model.DnsMode.PLAIN, vm.uiState.value.dnsMode)
    }

    @Test
    fun `the DNS-noise toggle is stored`() = runTest {
        val vm = viewModel()
        vm.setRouteDnsNoise(true)
        assertTrue(vm.uiState.value.routeDnsNoise)
    }

    // --- #281 search-engine opt-out ---

    @Test
    fun `an engine can be opted out of the rotation`() = runTest {
        val vm = viewModel()
        vm.setSearchEngineEnabled("duckduckgo", false)
        advanceUntilIdle()
        assertTrue(
            "the opted-out engine is recorded as excluded",
            vm.uiState.value.excludedSearchEngines.contains("duckduckgo")
        )
    }

    @Test
    fun `opting an engine back in clears the exclusion`() = runTest {
        val vm = viewModel()
        vm.setSearchEngineEnabled("duckduckgo", false)
        vm.setSearchEngineEnabled("duckduckgo", true)
        advanceUntilIdle()
        assertTrue(
            "re-enabling removes the exclusion",
            vm.uiState.value.excludedSearchEngines.isEmpty()
        )
    }

    @Test
    fun `the engine pool cannot be driven below the diversity floor`() = runTest {
        // Collapsing synthetic search onto one SERP would make it trivially separable
        // from real traffic, so the last two engines are pinned on.
        val vm = viewModel()
        SEARCH_ENGINE_IDS.forEach { vm.setSearchEngineEnabled(it, false) }
        advanceUntilIdle()
        val remaining = SEARCH_ENGINE_IDS.count { it !in vm.uiState.value.excludedSearchEngines }
        assertEquals(
            "at least MIN_ACTIVE_SEARCH_ENGINES engines must survive",
            MIN_ACTIVE_SEARCH_ENGINES,
            remaining
        )
    }

    @Test
    fun `isLastRequiredSearchEngine marks exactly the pinned engines`() = runTest {
        val vm = viewModel()
        // With all five active, none is pinned yet.
        assertFalse(vm.uiState.value.isLastRequiredSearchEngine("google"))

        SEARCH_ENGINE_IDS.forEach { vm.setSearchEngineEnabled(it, false) }
        advanceUntilIdle()
        val state = vm.uiState.value
        val active = SEARCH_ENGINE_IDS.filter { it !in state.excludedSearchEngines }
        assertTrue("every surviving engine is pinned", active.all { state.isLastRequiredSearchEngine(it) })
        assertFalse(
            "an already-excluded engine is not pinned",
            state.excludedSearchEngines.any { state.isLastRequiredSearchEngine(it) }
        )
    }

    @Test
    fun `resetToDefaults clears the encrypted logs and the trained Markov model`() = runTest {
        viewModel().resetToDefaults()
        advanceUntilIdle()

        // The recoverable-trail wipes that were previously missing (the bug):
        verify { encryptedFileTree.clearLogs() }
        verify { markovGenerator.clearAllState() }
        // Regression guard for the data-store wipes it already performed:
        coVerify { demographicDao.delete() }
        coVerify { platformDao.deleteAll() }
        coVerify { personaHistoryDao.deleteAll() }
        // Audit fix: imported broker-profile history (incl. the clean control account #172) must
        // also be wiped, or it survives "delete all data" and resurfaces in the dashboard cards.
        coVerify { profileSnapshotDao.deleteAll() }
        // E10 (#177): the learned daily-rhythm histogram is part of the trail wipe.
        coVerify { circadianObserver.clear() }
    }
}
