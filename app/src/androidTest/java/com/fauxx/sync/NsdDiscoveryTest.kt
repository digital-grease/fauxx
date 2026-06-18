package com.fauxx.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.sync.discovery.NsdDiscovery
import com.fauxx.sync.wire.SyncConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented TXT-assembly test (mirror the desktop `service_info_carries_txt_record`). Builds the
 * advertised [android.net.nsd.NsdServiceInfo] without registering it, so it needs a device for the
 * NSD framework class but NOT multicast. The live advertise/browse path is multicast-dependent and
 * verified manually against `fauxx serve --lan-sync` (the desktop's own live discovery is #[ignore]d
 * for the same reason).
 */
@RunWith(AndroidJUnit4::class)
class NsdDiscoveryTest {

    private fun discovery() = NsdDiscovery(ApplicationProvider.getApplicationContext())

    @Test
    fun service_info_carries_txt_record() {
        val info = discovery().buildServiceInfo(
            instanceName = "Fauxx-Phone",
            port = SyncConstants.DEFAULT_SYNC_PORT,
            publicKeyB64 = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc",
            fingerprint = "1a2b:3c4d:5e6f:7081"
        )
        // NsdManager may normalize the type string (e.g. add a trailing dot); assert it carries the
        // NSD-flavored type without the trailing ".local." either way.
        assertTrue(
            "unexpected service type ${info.serviceType}",
            info.serviceType.contains(SyncConstants.NSD_SERVICE_TYPE)
        )
        assertEquals("Fauxx-Phone", info.serviceName)
        assertEquals(SyncConstants.DEFAULT_SYNC_PORT, info.port)

        val attrs = info.attributes
        assertNotNull(attrs[SyncConstants.TXT_KEY_VERSION])
        assertEquals(
            SyncConstants.PROTOCOL_VERSION.toString(),
            String(attrs[SyncConstants.TXT_KEY_VERSION]!!, Charsets.UTF_8)
        )
        assertEquals("1a2b:3c4d:5e6f:7081", String(attrs[SyncConstants.TXT_KEY_FINGERPRINT]!!, Charsets.UTF_8))
        assertEquals(
            "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc",
            String(attrs[SyncConstants.TXT_KEY_PUBKEY]!!, Charsets.UTF_8)
        )
    }
}
