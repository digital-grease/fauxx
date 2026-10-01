package com.fauxx

import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.modules.Module
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.network.dns.DnsHealth
import com.fauxx.support.FakeClock
import com.fauxx.targeting.TargetingEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Engine lifecycle around custom DNS (#227).
 *
 * The regression this pins: [PoisonEngine.stop] tears down asynchronously, waiting up to two
 * seconds for module stops before it reaches `customDns.stop()`. An off/on toggle used to let the
 * NEW session's `customDns.start()` run inside that window, and the old teardown's stop then
 * landed after it, silently turning custom DNS off for the whole new session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineCustomDnsTest {

    private lateinit var engine: PoisonEngine

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.destroy()
    }

    private class RecordingDns : CustomDns {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val health: StateFlow<DnsHealth> = MutableStateFlow(DnsHealth.Off)
        override suspend fun start() { calls += "start" }
        override suspend fun stop() { calls += "stop" }
    }

    @Test
    fun `a quick restart never lets the old teardown stop the new session's custom DNS`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dns = RecordingDns()
        val clock = FakeClock(noonEpochMs())
        engine = buildEngine(dns, dispatcher, clock)

        // The engine loop never goes idle, so it must be stopped even when an assertion fails;
        // otherwise runTest's end-of-test drain spins on it until the JVM runs out of memory.
        try {
            engine.start()
            advance(clock, 100)
            assertEquals(listOf("start"), dns.calls.toList())

            // Off and straight back on, while the old teardown is still waiting on a slow module stop.
            engine.stop()
            engine.start()
            advance(clock, 3_000)

            assertEquals(
                "the old session's stop must land before the new session's start",
                listOf("start", "stop", "start"),
                dns.calls.toList(),
            )
        } finally {
            engine.stop()
        }
    }

    /**
     * Advance virtual time and the fake clock together, as the other engine tests do: the loop's
     * waits are measured on the clock, so a frozen clock makes it spin.
     */
    private fun kotlinx.coroutines.test.TestScope.advance(clock: FakeClock, ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val chunk = minOf(100L, remaining)
            clock.nowMs += chunk
            testScheduler.advanceTimeBy(chunk)
            testScheduler.runCurrent()
            remaining -= chunk
        }
    }

    /** Today's local 12:00, inside every active-hours window. */
    private fun noonEpochMs(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 12)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun buildEngine(
        dns: CustomDns,
        loopDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        clock: FakeClock,
    ): PoisonEngine {
        // Nothing browses; the DNS-noise module takes a second to stop, the way a module closing
        // WebViews does.
        fun module(): Module = mockk(relaxed = true) { every { isEnabled() } returns false }
        val connectivityManager: android.net.ConnectivityManager = mockk(relaxed = true)
        val context: android.content.Context = mockk(relaxed = true) {
            every { getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        }
        return PoisonEngine(
            context = context,
            profile = mockk<PoisonProfileRepository> { every { getProfile() } returns PoisonProfile(enabled = true, batteryThresholdBattery = 0, batteryThresholdCharging = 0, allowedHoursStart = 0, allowedHoursEnd = 24) },
            targetingEngine = mockk<TargetingEngine>(relaxed = true),
            dispatcher = mockk<ActionDispatcher> { coEvery { selectCategory() } returns CategoryPool.GAMING },
            scheduler = mockk<PoissonScheduler> { every { nextDelayMs(any(), any(), any(), any(), any()) } returns 1_000L },
            actionLogDao = mockk<ActionLogDao>(relaxed = true),
            blocklist = mockk<DomainBlocklist> { every { loadFailed } returns false },
            queryBankManager = mockk<QueryBankManager> {
                every { corpusGeneration } returns 0
                every { getQueries(any()) } returns listOf("test")
            },
            crawlListManager = mockk<CrawlListManager> { every { corpusSize() } returns 100 },
            cityDatabase = mockk<CityDatabase> {
                every { cities } returns listOf(CityCoord("A", 0.0, 0.0, "X"), CityCoord("B", 1.0, 1.0, "X"))
            },
            searchModule = mockk(relaxed = true) { every { isEnabled() } returns false },
            adModule = module(),
            locationModule = module(),
            fingerprintModule = mockk(relaxed = true) { every { isEnabled() } returns false },
            cookieModule = mockk(relaxed = true) { every { isEnabled() } returns false },
            appSignalModule = mockk(relaxed = true) { every { isEnabled() } returns false },
            // Enabled so every loop iteration does real work and waits its scheduled delay; with no
            // enabled module the loop would spin in virtual time.
            dnsModule = mockk(relaxed = true) {
                every { isEnabled() } returns true
                coEvery { onAction(any()) } returns ActionLogEntity(
                    actionType = ActionType.DNS_LOOKUP, category = CategoryPool.GAMING, detail = "test", success = true,
                )
                coEvery { stop() } coAnswers { delay(1_000) }
            },
            clock = clock,
            loopDispatcher = loopDispatcher,
            customDns = dns,
        )
    }
}
