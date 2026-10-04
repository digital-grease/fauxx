package com.fauxx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.EngineState
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.WebViewCapabilities
import com.fauxx.support.FakeClock
import com.fauxx.support.MainDispatcherRule
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.fauxx.ui.viewmodels.DashboardViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the rolling action-count window. The "today" / "this week" counts use
 * `countSince(now - window)`; computing `now` once at flow-construction froze the boundary,
 * so the window silently widened the longer the dashboard stayed open. The boundary must
 * slide as time passes.
 *
 * The test dispatcher is shared with the rule (so viewModelScope and runTest use one
 * scheduler) — otherwise advancing virtual time wouldn't drive the ViewModel's ticker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val context: Context = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile() } returns PoisonProfile()
        // Must actually emit: a relaxed-mock Flow never emits, and combine() in uiState
        // only produces values once ALL source flows have emitted (issue #62 added this).
        every { profiles } returns MutableStateFlow(PoisonProfile())
    }
    private val poisonEngine: PoisonEngine = mockk(relaxed = true) {
        every { healthWarnings } returns MutableStateFlow(emptyList())
        every { engineState } returns MutableStateFlow(EngineState.STOPPED)
    }
    private val targetingEngine: TargetingEngine = mockk(relaxed = true) {
        every { getWeights() } returns MutableStateFlow(emptyMap<CategoryPool, Float>())
    }
    private val personaLayer: PersonaRotationLayer = mockk(relaxed = true) {
        every { currentPersona } returns MutableStateFlow<SyntheticPersona?>(null)
    }
    private val dataStore: DataStore<Preferences> = mockk(relaxed = true) {
        every { data } returns flowOf(emptyPreferences())
    }
    private val actionLogDao: ActionLogDao = mockk(relaxed = true)

    @Test
    fun `rolling action-count window boundary slides as time passes`() = runTest(testDispatcher) {
        val clock = FakeClock(1_700_000_000_000L)
        val boundaries = java.util.Collections.synchronizedList(mutableListOf<Long>())
        every { actionLogDao.countSince(capture(boundaries)) } returns MutableStateFlow(0)

        val vm = DashboardViewModel(
            context, actionLogDao, profileRepo, poisonEngine, targetingEngine,
            personaLayer, dataStore,
            mockk<com.fauxx.targeting.layer2.ProfileSnapshotDao>(relaxed = true) {
                every { observeAll() } returns MutableStateFlow(emptyList<com.fauxx.targeting.layer2.ProfileSnapshot>())
            },
            com.fauxx.targeting.layer2.ProfileDriftMetric(), clock
        )
        val job = launch { vm.uiState.collect {} }
        testScheduler.runCurrent()
        val maxBefore = boundaries.maxOrNull()

        // Advance past the 60s window-refresh interval, moving the clock in lockstep.
        clock.nowMs += 5 * 60_000L
        testScheduler.advanceTimeBy(5 * 60_000L)
        testScheduler.runCurrent()
        val maxAfter = boundaries.maxOrNull()
        job.cancel()

        assertNotNull("countSince must be invoked at least once", maxBefore)
        assertTrue(
            "the rolling-window boundary must advance as time passes (it was frozen at " +
                "construction); boundaries=$boundaries",
            maxAfter != null && maxAfter > maxBefore!!
        )
    }

    private fun newViewModel(
        canHidePackageName: Boolean,
        customDns: com.fauxx.engine.dns.CustomDns = com.fauxx.engine.dns.CustomDns.NONE,
    ) = DashboardViewModel(
        context, actionLogDao, profileRepo, poisonEngine, targetingEngine, personaLayer, dataStore,
        mockk<com.fauxx.targeting.layer2.ProfileSnapshotDao>(relaxed = true) {
            every { observeAll() } returns MutableStateFlow(emptyList<com.fauxx.targeting.layer2.ProfileSnapshot>())
        },
        com.fauxx.targeting.layer2.ProfileDriftMetric(), FakeClock(0L),
        object : WebViewCapabilities {
            override fun canHidePackageName(): Boolean = canHidePackageName
            override fun canUseProxy(): Boolean = true
        },
        customDns,
    )

    @Test
    fun `warns when the WebView cannot hide the package name`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        val vm = newViewModel(canHidePackageName = false)
        // The check runs on Dispatchers.Default, a real thread, so wait for it rather than assume.
        assertTrue(withTimeout(5_000) { vm.webViewNamesApp.first { it } })
    }

    @Test
    fun `stays quiet when the WebView can hide the package name`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        val vm = newViewModel(canHidePackageName = true)
        delay(200)
        assertFalse(vm.webViewNamesApp.value)
    }

    @Test
    fun `the quiet DNS line follows the custom resolver's health`() = runBlocking {
        every { actionLogDao.countSince(any()) } returns MutableStateFlow(0)
        val health = MutableStateFlow<com.fauxx.network.dns.DnsHealth>(com.fauxx.network.dns.DnsHealth.Healthy)
        val dns = object : com.fauxx.engine.dns.CustomDns {
            override val health = health
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun noiseResolver(): com.fauxx.network.dns.HostResolver? = null
        }
        val vm = newViewModel(canHidePackageName = true, customDns = dns)
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.customDnsNotice.collect {} }
        assertEquals(null, vm.customDnsNotice.value)
        health.value = com.fauxx.network.dns.DnsHealth.Degraded(0L, "down")
        assertEquals(com.fauxx.ui.viewmodels.CustomDnsNotice.FALLBACK, withTimeout(5_000) { vm.customDnsNotice.first { it != null } })
        // Interception gets its own wording: then Fauxx is NOT on the device's DNS.
        health.value = com.fauxx.network.dns.DnsHealth.Degraded(0L, "intercepted", intercepted = true)
        assertEquals(
            com.fauxx.ui.viewmodels.CustomDnsNotice.INTERCEPTED,
            withTimeout(5_000) { vm.customDnsNotice.first { it == com.fauxx.ui.viewmodels.CustomDnsNotice.INTERCEPTED } },
        )
        // A changed certificate gets its own wording too: the user may have caused it and can fix it.
        health.value = com.fauxx.network.dns.DnsHealth.Degraded(0L, "cert", certificateChanged = true)
        assertEquals(
            com.fauxx.ui.viewmodels.CustomDnsNotice.CERTIFICATE_CHANGED,
            withTimeout(5_000) { vm.customDnsNotice.first { it == com.fauxx.ui.viewmodels.CustomDnsNotice.CERTIFICATE_CHANGED } },
        )
        job.cancel()
    }
}
