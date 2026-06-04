package com.fauxx.engine.modules

import android.content.Context
import android.location.LocationManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Locks the orphaned-mock-location cleanup (finding #6 / issue #66): a process hard-killed before
 * stop() runs leaves the system test provider registered, so the device keeps reporting the last
 * spoofed fix. [MockLocationProviderCleaner] sweeps it unconditionally — regardless of any
 * in-process "provider added" flag, which a fresh post-restart instance would have as false — and
 * must never throw on the common "already clean" states, or it would crash app startup.
 */
class MockLocationProviderCleanerTest {

    private lateinit var context: Context
    private lateinit var locationManager: LocationManager
    private lateinit var cleaner: MockLocationProviderCleaner

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        locationManager = mockk()
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        cleaner = MockLocationProviderCleaner(context)
    }

    @Test
    fun `clearOrphanedProvider removes the test provider by the shared name`() {
        every { locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER) } just runs

        cleaner.clearOrphanedProvider()

        verify(exactly = 1) { locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER) }
    }

    @Test
    fun `clearOrphanedProvider swallows IllegalArgumentException when no provider exists`() {
        // First launch / already-clean: removeTestProvider throws for an unknown provider name.
        every {
            locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER)
        } throws IllegalArgumentException("Provider \"fauxx_mock\" unknown")

        cleaner.clearOrphanedProvider() // must not throw — runs on the cold-start path

        verify(exactly = 1) { locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER) }
    }

    @Test
    fun `clearOrphanedProvider swallows SecurityException when no longer the mock app`() {
        every {
            locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER)
        } throws SecurityException("not the selected mock-location app")

        cleaner.clearOrphanedProvider() // must not throw

        verify(exactly = 1) { locationManager.removeTestProvider(MOCK_LOCATION_PROVIDER) }
    }
}
