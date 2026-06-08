package com.fauxx.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.di.PreferenceKeys
import com.fauxx.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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
            mobileIntensity = IntensityLevel.LOW,            // default null (paused on mobile)
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
    fun `all IntensityLevel values survive the mobileIntensity round-trip`() {
        for (level in IntensityLevel.values()) {
            runBlocking { repo.saveProfile(PoisonProfile(mobileIntensity = level)) }

            assertEquals(
                "mobileIntensity $level must survive the enum-name round-trip",
                level,
                readBack().mobileIntensity,
            )
        }
    }

    // --- mobile_intensity persistence + lazy wifi_only migration (issue #62) ---

    /** Writes raw preference keys, simulating a store written by an older app version. */
    private fun seedRawPrefs(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) =
        runBlocking { dataStore.edit { block(it) } }

    @Test
    fun `null mobileIntensity persists as the OFF sentinel plus derived legacy wifi_only`() {
        runBlocking { repo.saveProfile(PoisonProfile(mobileIntensity = null)) }

        val prefs = runBlocking { dataStore.data.first() }
        assertEquals("OFF", prefs[PreferenceKeys.MOBILE_INTENSITY])
        assertEquals(
            "wifi_only must be derived true for downgrade safety",
            true,
            prefs[PreferenceKeys.WIFI_ONLY],
        )
        assertNull(readBack().mobileIntensity)
    }

    @Test
    fun `non-null mobileIntensity derives legacy wifi_only false`() {
        runBlocking { repo.saveProfile(PoisonProfile(mobileIntensity = IntensityLevel.HIGH)) }

        val prefs = runBlocking { dataStore.data.first() }
        assertEquals("HIGH", prefs[PreferenceKeys.MOBILE_INTENSITY])
        assertEquals(false, prefs[PreferenceKeys.WIFI_ONLY])
    }

    @Test
    fun `legacy wifi_only true migrates to paused-on-mobile`() {
        seedRawPrefs { it[PreferenceKeys.WIFI_ONLY] = true }

        assertNull(readBack().mobileIntensity)
    }

    @Test
    fun `legacy wifi_only false migrates to mirroring the wifi intensity`() {
        // Pre-0.3.2, wifiOnly=false meant "run the single intensity on any network".
        seedRawPrefs {
            it[PreferenceKeys.WIFI_ONLY] = false
            it[PreferenceKeys.INTENSITY] = IntensityLevel.HIGH.name
        }

        assertEquals(IntensityLevel.HIGH, readBack().mobileIntensity)
    }

    @Test
    fun `explicit OFF sentinel wins over legacy wifi_only false`() {
        seedRawPrefs {
            it[PreferenceKeys.MOBILE_INTENSITY] = "OFF"
            it[PreferenceKeys.WIFI_ONLY] = false
        }

        assertNull(readBack().mobileIntensity)
    }

    @Test
    fun `explicit mobile tier wins over legacy wifi_only true`() {
        seedRawPrefs {
            it[PreferenceKeys.MOBILE_INTENSITY] = IntensityLevel.LOW.name
            it[PreferenceKeys.WIFI_ONLY] = true
        }

        assertEquals(IntensityLevel.LOW, readBack().mobileIntensity)
    }

    @Test
    fun `corrupt mobile_intensity value fails safe to paused-on-mobile`() {
        seedRawPrefs { it[PreferenceKeys.MOBILE_INTENSITY] = "TURBO" }

        assertNull(
            "an unknown stored tier must never burn mobile data",
            readBack().mobileIntensity,
        )
    }

    @Test
    fun `corrupt intensity value falls back to MEDIUM instead of crashing the read`() {
        seedRawPrefs { it[PreferenceKeys.INTENSITY] = "LUDICROUS" }

        assertEquals(IntensityLevel.MEDIUM, readBack().intensity)
    }

    @Test
    fun `updateProfile applies a transform atomically without clobbering unrelated fields`() {
        val seeded = PoisonProfile(
            intensity = IntensityLevel.HIGH,
            batteryThreshold = 42,
            customUserAgent = "Mozilla/5.0 (seeded)",
            mobileIntensity = IntensityLevel.MEDIUM,
        )
        runBlocking { repo.saveProfile(seeded) }

        // Transform only the `enabled` flag; everything else the transform leaves untouched
        // must remain exactly as seeded.
        runBlocking { repo.updateProfile { it.copy(enabled = true) } }

        val result = readBack()
        assertEquals(seeded.copy(enabled = true), result)
    }
}
