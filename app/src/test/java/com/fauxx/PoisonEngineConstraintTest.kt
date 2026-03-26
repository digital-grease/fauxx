package com.fauxx

import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.AdPollutionModule
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.LocationSpoofModule
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.targeting.TargetingEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineConstraintTest {

    private val profile = PoisonProfile(
        enabled = true,
        intensity = IntensityLevel.LOW,
        wifiOnly = false,
        batteryThreshold = 20,
        allowedHoursStart = 0,
        allowedHoursEnd = 24,
        searchPoisonEnabled = true,
        adPollutionEnabled = false,
        locationSpoofEnabled = false,
        fingerprintEnabled = false,
        cookieSaturationEnabled = false,
        appSignalEnabled = false,
        dnsNoiseEnabled = false,
        layer1Enabled = false,
        layer2Enabled = false,
        layer3Enabled = false
    )

    private val profileRepo: PoisonProfileRepository = mockk {
        every { getProfile() } returns profile
    }
    private val dispatcher: ActionDispatcher = mockk {
        coEvery { selectCategory() } returns CategoryPool.GAMING
    }
    private val scheduler: PoissonScheduler = mockk {
        every { nextDelayMs(any(), any(), any()) } returns 100L
    }
    private val actionLogDao: ActionLogDao = mockk(relaxed = true)

    private val searchModule: SearchPoisonModule = mockk(relaxed = true)
    private val adModule: AdPollutionModule = mockk(relaxed = true)
    private val locationModule: LocationSpoofModule = mockk(relaxed = true)
    private val fingerprintModule: FingerprintModule = mockk(relaxed = true)
    private val cookieModule: CookieSaturationModule = mockk(relaxed = true)
    private val appSignalModule: AppSignalModule = mockk(relaxed = true)
    private val dnsModule: DnsNoiseModule = mockk(relaxed = true)

    private lateinit var engine: PoisonEngine

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.destroy()
        }
    }

    @Test
    fun `engine calls destroy without crashing`() {
        engine = createEngine()
        engine.start()
        engine.destroy()
        // No exception = pass. Verifies destroy cancels scope cleanly.
    }

    @Test
    fun `engine stops gracefully when no modules enabled`() = runTest {
        // Disable all modules
        every { searchModule.isEnabled() } returns false
        every { adModule.isEnabled() } returns false
        every { locationModule.isEnabled() } returns false
        every { fingerprintModule.isEnabled() } returns false
        every { cookieModule.isEnabled() } returns false
        every { appSignalModule.isEnabled() } returns false
        every { dnsModule.isEnabled() } returns false

        engine = createEngine()
        engine.start()
        delay(200)
        engine.destroy()
        // Engine should have stayed in its loop without crashing
    }

    private fun createEngine(): PoisonEngine {
        // Set up mock returns for isEnabled
        every { searchModule.isEnabled() } returns true
        every { adModule.isEnabled() } returns false
        every { locationModule.isEnabled() } returns false
        every { fingerprintModule.isEnabled() } returns false
        every { cookieModule.isEnabled() } returns false
        every { appSignalModule.isEnabled() } returns false
        every { dnsModule.isEnabled() } returns false

        val connectivityManager: android.net.ConnectivityManager = mockk(relaxed = true)
        val context: android.content.Context = mockk(relaxed = true) {
            every { getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        }
        val targetingEngine: TargetingEngine = mockk(relaxed = true) {
            every { setLayer1Enabled(any()) } answers { }
            every { setLayer2Enabled(any()) } answers { }
            every { setLayer3Enabled(any()) } answers { }
        }
        return PoisonEngine(
            context, profileRepo, targetingEngine, dispatcher, scheduler, actionLogDao,
            searchModule, adModule, locationModule, fingerprintModule,
            cookieModule, appSignalModule, dnsModule
        )
    }
}
