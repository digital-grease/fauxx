package com.fauxx.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit test for [PoisonProfileRepository] (declared inside `PoisonEngine.kt`) — proves the
 * DataStore mapper round-trips every field of [PoisonProfile] and handles the customUserAgent
 * null/blank-collapse contract.
 *
 * Read-back strategy: this test never asserts via [PoisonProfileRepository.getProfile]. That
 * accessor is fed by a background `Dispatchers.IO` `collect` seeded in the repository's `init`
 * block, so it returns the safe default until an emission lands and is therefore racy under a
 * synchronous test. Instead each case reads back through [PoisonProfileRepository.updateProfile],
 * whose `transform` runs `prefsToProfile(prefs)` synchronously inside the DataStore `edit`
 * transaction — a deterministic, blocking deserialize of exactly what was persisted. The
 * transform captures the read value and returns it unchanged, so the write is a no-op.
 *
 * Isolation: a fresh on-disk preferences file (unique per test via `System.nanoTime()`) backs a
 * brand-new [DataStore] for every case, driven by an [UnconfinedTestDispatcher] so the edit
 * transaction completes inline under [runBlocking]. [tearDown] cancels the repository's
 * background collector via [PoisonProfileRepository.close].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonProfileRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: PoisonProfileRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        ) {
            File(context.cacheDir, "rt_" + System.nanoTime() + ".preferences_pb")
        }
        repo = PoisonProfileRepository(dataStore)
    }

    @After
    fun tearDown() {
        repo.close()
    }

    /**
     * Deterministically reads back whatever is currently persisted by running a no-op
     * [PoisonProfileRepository.updateProfile] transform that captures the synchronously
     * deserialized profile and returns it unchanged.
     */
    private fun readBack(): PoisonProfile = runBlocking {
        var captured: PoisonProfile? = null
        repo.updateProfile { read ->
            captured = read
            read
        }
        captured!!
    }

    @Test
    fun `round-trips a fully non-default profile through DataStore`() {
        // Every field flipped off its PoisonProfile() default; a single data-class equality
        // check then proves the entire profileToPrefs / prefsToProfile mapping at once.
        val input = PoisonProfile(
            enabled = true,                                  // default false
            intensity = IntensityLevel.HIGH,                 // default MEDIUM
            wifiOnly = false,                                // default true
            batteryThreshold = 73,                           // default 20
            ignoreBatteryThresholdWhileCharging = true,      // default false
            allowedHoursStart = 3,                           // default 7
            allowedHoursEnd = 19,                            // default 23
            searchPoisonEnabled = false,                     // default true
            adPollutionEnabled = false,                      // default true
            locationSpoofEnabled = true,                     // default false
            fingerprintEnabled = false,                      // default true
            cookieSaturationEnabled = false,                 // default true
            appSignalEnabled = true,                         // default false
            dnsNoiseEnabled = false,                         // default true
            layer1Enabled = true,                            // default false
            layer2Enabled = true,                            // default false
            layer3Enabled = false,                           // default true
            themeMode = ThemeMode.DARK,                      // default SYSTEM
            resumeOnBoot = false,                            // default true
            customUserAgent = "Mozilla/5.0 (Fauxx-test-UA)", // default null
        )

        runBlocking { repo.saveProfile(input) }

        assertEquals(input, readBack())
    }

    @Test
    fun `first-run empty store yields PoisonProfile defaults`() {
        // Nothing is saved; the untouched store has no keys, so every prefsToProfile elvis
        // fallback fires and the read must equal the data-class defaults.
        assertEquals(PoisonProfile(), readBack())
    }

    @Test
    fun `round-trips the default profile explicitly`() {
        val input = PoisonProfile()

        runBlocking { repo.saveProfile(input) }

        assertEquals(input, readBack())
    }

    @Test
    fun `null customUserAgent is persisted as key removal and reads back null`() {
        // First persist a real UA so the key exists, then save null and confirm the
        // key was removed (read-back is null, not a stale string).
        runBlocking {
            repo.saveProfile(PoisonProfile(customUserAgent = "Mozilla/5.0 (set-first)"))
            repo.saveProfile(PoisonProfile(customUserAgent = null))
        }

        assertNull(readBack().customUserAgent)
    }

    @Test
    fun `blank customUserAgent collapses to null`() {
        runBlocking { repo.saveProfile(PoisonProfile(customUserAgent = "   ")) }

        assertNull(readBack().customUserAgent)
    }

    @Test
    fun `all IntensityLevel values survive the string round-trip`() {
        for (level in IntensityLevel.values()) {
            runBlocking { repo.saveProfile(PoisonProfile(intensity = level)) }

            assertEquals(
                "intensity $level must survive the enum-name round-trip",
                level,
                readBack().intensity,
            )
        }
    }

    @Test
    fun `all ThemeMode values survive the string round-trip`() {
        for (mode in ThemeMode.values()) {
            runBlocking { repo.saveProfile(PoisonProfile(themeMode = mode)) }

            assertEquals(
                "themeMode $mode must survive the enum-name round-trip",
                mode,
                readBack().themeMode,
            )
        }
    }

    @Test
    fun `updateProfile applies a transform atomically without clobbering unrelated fields`() {
        val seeded = PoisonProfile(
            intensity = IntensityLevel.HIGH,
            batteryThreshold = 42,
            customUserAgent = "Mozilla/5.0 (seeded)",
            wifiOnly = false,
        )
        runBlocking { repo.saveProfile(seeded) }

        // Transform only the `enabled` flag; everything else the transform leaves untouched
        // must remain exactly as seeded.
        runBlocking { repo.updateProfile { it.copy(enabled = true) } }

        val result = readBack()
        assertEquals(seeded.copy(enabled = true), result)
    }
}
