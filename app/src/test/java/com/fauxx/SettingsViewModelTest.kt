package com.fauxx

import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.PoisonProfile
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

    // --- #201 custom-UA quick wins ---

    @Test
    fun `setCustomUserAgent strips the WebView wv marker`() = runTest {
        val vm = viewModel()
        vm.setCustomUserAgent(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        advanceUntilIdle()
        val ua = vm.uiState.value.customUserAgent
        assertFalse("the '; wv' WebView marker must be stripped", ua.contains("; wv"))
        assertTrue("the rest of the UA is preserved", ua.contains("Chrome/120.0.0.0"))
    }

    @Test
    fun `customUserAgentIsNonChromium flags a Firefox UA but not a Chrome one`() = runTest {
        val vm = viewModel()
        vm.setCustomUserAgent("Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
        advanceUntilIdle()
        assertTrue("a Firefox UA is non-Chromium and ignored on the WebView path",
            vm.uiState.value.customUserAgentIsNonChromium)

        vm.setCustomUserAgent(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        advanceUntilIdle()
        assertFalse("a Chrome-on-Android UA must not be flagged",
            vm.uiState.value.customUserAgentIsNonChromium)
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
