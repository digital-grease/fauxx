package com.fauxx.engine

import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the WiFi-detection contract for issue #59: when a per-app VPN like
 * TrackerControl is active, the OS-active network is the VPN tunnel
 * (`TRANSPORT_VPN`, no `TRANSPORT_WIFI`). A plain `hasTransport(TRANSPORT_WIFI)`
 * check returns false even when the user is physically on WiFi — making the
 * engine pause with "waiting for WiFi" forever.
 *
 * [PoisonEngine.isWifiActive] resolves this by walking underlying networks when
 * the active transport is VPN.
 */
class PoisonEngineWifiDetectionTest {

    private fun caps(vararg transports: Int): NetworkCapabilities = mockk(relaxed = true) {
        // Default: no transport returns true.
        every { hasTransport(any()) } returns false
        // Whitelisted transports return true.
        for (transport in transports) {
            every { hasTransport(transport) } returns true
        }
    }

    @Test
    fun `null active caps returns false`() {
        assertFalse(PoisonEngine.isWifiActive(null) { emptyList() })
    }

    @Test
    fun `direct WiFi connection returns true`() {
        val activeWifi = caps(NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(PoisonEngine.isWifiActive(activeWifi) { emptyList() })
    }

    @Test
    fun `cellular only returns false`() {
        val cellular = caps(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(PoisonEngine.isWifiActive(cellular) { emptyList() })
    }

    @Test
    fun `VPN over WiFi returns true via underlying network scan`() {
        // The TrackerControl scenario: active network is the VPN tunnel; the user's
        // physical WiFi connection only shows up via the underlying-networks lookup.
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        val physicalWifi = caps(NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(PoisonEngine.isWifiActive(activeVpn) { listOf(physicalWifi) })
    }

    @Test
    fun `VPN over cellular returns false`() {
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        val physicalCellular = caps(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(PoisonEngine.isWifiActive(activeVpn) { listOf(physicalCellular) })
    }

    @Test
    fun `VPN with no underlying networks returns false`() {
        // Edge case: VPN is active but the underlying-networks lookup returned nothing
        // (e.g., transient state during VPN tunnel teardown). Conservative — pause.
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        assertFalse(PoisonEngine.isWifiActive(activeVpn) { emptyList() })
    }

    @Test
    fun `VPN ignores VPN-tagged entries in underlying list`() {
        // Sanity: the underlying-network scan must not loop back to the VPN itself.
        // `cm.allNetworks` on the legacy fallback path includes ALL networks including
        // VPN ones — the helper must filter them out so a stacked VPN over a VPN
        // doesn't look like WiFi.
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        val anotherVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        assertFalse(PoisonEngine.isWifiActive(activeVpn) { listOf(anotherVpn) })
    }
}
