package com.fauxx.engine

import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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

    // --- classifyTransport (issue #62: per-network intensity needs WIFI/CELLULAR/NONE) ---

    @Test
    fun `classifyTransport maps null caps to NONE`() {
        assertEquals(NetworkTransport.NONE, PoisonEngine.classifyTransport(null) { emptyList() })
    }

    @Test
    fun `classifyTransport maps WiFi to WIFI`() {
        val wifi = caps(NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetworkTransport.WIFI, PoisonEngine.classifyTransport(wifi) { emptyList() })
    }

    @Test
    fun `classifyTransport maps ethernet to the unmetered WIFI bucket`() {
        val ethernet = caps(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetworkTransport.WIFI, PoisonEngine.classifyTransport(ethernet) { emptyList() })
    }

    @Test
    fun `classifyTransport maps cellular to CELLULAR`() {
        val cellular = caps(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertEquals(NetworkTransport.CELLULAR, PoisonEngine.classifyTransport(cellular) { emptyList() })
    }

    @Test
    fun `classifyTransport maps VPN over WiFi to WIFI`() {
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        val physicalWifi = caps(NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetworkTransport.WIFI, PoisonEngine.classifyTransport(activeVpn) { listOf(physicalWifi) })
    }

    @Test
    fun `classifyTransport bills VPN over cellular as CELLULAR`() {
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        val physicalCellular = caps(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertEquals(NetworkTransport.CELLULAR, PoisonEngine.classifyTransport(activeVpn) { listOf(physicalCellular) })
    }

    @Test
    fun `classifyTransport maps VPN over ethernet to WIFI like bare ethernet`() {
        // The underlying scan must use the same unmetered bucket as the direct branch,
        // or enabling a VPN on an ethernet device would silently drop to the mobile tier.
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        val physicalEthernet = caps(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetworkTransport.WIFI, PoisonEngine.classifyTransport(activeVpn) { listOf(physicalEthernet) })
    }

    @Test
    fun `classifyTransport bills VPN with unknown underlying as CELLULAR`() {
        // When in doubt about what a VPN tunnels over, bill it as mobile data so the
        // engine can never exceed the user's mobile budget by accident.
        val activeVpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        assertEquals(NetworkTransport.CELLULAR, PoisonEngine.classifyTransport(activeVpn) { emptyList() })
    }

    @Test
    fun `classifyTransport bills unknown connected transports as CELLULAR`() {
        // E.g. bluetooth tethering: connected, not WiFi, not declared cellular — metered
        // until proven otherwise.
        val mystery = caps(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        assertEquals(NetworkTransport.CELLULAR, PoisonEngine.classifyTransport(mystery) { emptyList() })
    }
}
