package com.fauxx.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Battery Saver pause on a real device (#313). The JVM tests drive the engine's cached power-save
 * state directly; this one proves the part they cannot: that Android actually delivers
 * ACTION_POWER_SAVE_MODE_CHANGED to the engine's not-exported receiver, so the pause starts when
 * Battery Saver turns on and ends the moment it turns off, well inside the 60-second re-check.
 *
 * Battery Saver is toggled through the shell (`cmd power set-mode`), with the battery reported as
 * unplugged first, since Android turns Battery Saver off while charging.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BatterySaverPauseTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var engine: PoisonEngine
    @Inject lateinit var profileRepo: PoisonProfileRepository

    private lateinit var saved: PoisonProfile

    @Before
    fun setUp() {
        hiltRule.inject()
        saved = profileRepo.getProfile()
        // No modules, so the engine only runs its constraint loop and never browses.
        val testProfile = PoisonProfile(
            enabled = true,
            intensity = IntensityLevel.LOW,
            mobileIntensity = IntensityLevel.LOW,
            batteryThresholdBattery = 0,
            batteryThresholdCharging = 0,
            pauseOnBatterySaver = true,
            allowedHoursStart = 0,
            allowedHoursEnd = 0,
            searchPoisonEnabled = false,
            adPollutionEnabled = false,
            fingerprintEnabled = false,
            cookieSaturationEnabled = false,
            dnsNoiseEnabled = false,
            layer3Enabled = false,
        )
        runBlocking { profileRepo.saveProfile(testProfile) }
        waitFor("the profile cache to pick up the test profile") { profileRepo.getProfile().pauseOnBatterySaver }
        shell("dumpsys battery unplug")
        shell("cmd power set-mode 0")
    }

    @After
    fun tearDown() {
        engine.stop()
        shell("cmd power set-mode 0")
        shell("dumpsys battery reset")
        runBlocking { profileRepo.saveProfile(saved) }
    }

    @Test
    fun batterySaverPausesTheEngineAndItsEndResumesItAtOnce() {
        engine.start()
        waitFor("the engine to run") { engine.engineState.value == EngineState.ACTIVE }

        shell("cmd power set-mode 1")
        waitFor("Battery Saver to pause the engine", timeoutMs = 75_000) {
            engine.engineState.value == EngineState.PAUSED_BATTERY_SAVER
        }

        val off = System.currentTimeMillis()
        shell("cmd power set-mode 0")
        waitFor("the engine to resume when Battery Saver ends", timeoutMs = 10_000) {
            engine.engineState.value == EngineState.ACTIVE
        }
        // Resuming inside 10 s (not on the 60-second re-check) is the broadcast doing its job.
        val tookMs = System.currentTimeMillis() - off
        assertEquals("resumed via the broadcast, took ${tookMs}ms", true, tookMs < 10_000)
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
        Thread.sleep(300)
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what (state=${engine.engineState.value})")
    }
}
