package com.fauxx.engine.modules

import android.content.Context
import android.location.LocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Name of the system test location provider Fauxx registers. Shared between
 * [LocationSpoofModule] (which adds it) and [MockLocationProviderCleaner] (which sweeps it) so
 * registration and cleanup can never drift to different names.
 */
const val MOCK_LOCATION_PROVIDER = "fauxx_mock"

/**
 * Removes Fauxx's system mock-location test provider. Deliberately lightweight — it depends only
 * on [Context] / [LocationManager], not the full [LocationSpoofModule] object graph — so the
 * process-start sweep can run without pulling route generation, the city database, and the
 * profile repository onto the cold-start path.
 *
 * Why this exists (finding #6 / issue #66): a process that is hard-killed — OEM battery manager,
 * OOM, or a swipe-away that skips `onDestroy` on some OEMs — never runs [LocationSpoofModule.stop],
 * so the test provider stays registered and the device keeps reporting the last spoofed fix until
 * something removes it. The in-module pre-remove only fires on the *next* spoof start, and a fresh
 * module instance after a process restart has `mockProviderAdded == false`, so its `stop()` would
 * skip the removal. Sweeping unconditionally at every process start (and on FGS teardown)
 * guarantees an orphaned provider can never outlive a single app restart.
 */
@Singleton
class MockLocationProviderCleaner @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Unconditionally remove the test provider. Safe to call when none is registered: an unknown
     * provider throws [IllegalArgumentException] and a process that is no longer the selected
     * mock-location app throws [SecurityException] — both mean "nothing to clean up", and the
     * system removes the provider itself when the selected mock app changes.
     */
    fun clearOrphanedProvider() {
        runCatching { locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER) }
            .onFailure { Timber.d("No mock-location provider to clear: ${it.message}") }
    }
}
