package com.fauxx.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DohPresetsTest {

    @Test
    fun `every preset is https with literal bootstrap addresses`() {
        for (preset in DohPresets.ALL) {
            assertTrue("${preset.id} must be a valid https URL", DohPresets.isValidCustomUrl(preset.url))
            assertTrue("${preset.id} needs bootstrap addresses", preset.bootstrap.isNotEmpty())
            // Literals only: a hostname here would need the system resolver, defeating the point.
            for (ip in preset.bootstrap) {
                assertTrue("${preset.id}: $ip must be an IP literal", ip.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' })
            }
            assertEquals(preset.bootstrap.size, preset.bootstrapAddresses().size)
        }
    }

    @Test
    fun `ids are unique and the default exists`() {
        assertEquals(DohPresets.ALL.size, DohPresets.ALL.map { it.id }.toSet().size)
        assertNotNull(DohPresets.byId(DohPresets.DEFAULT_ID))
        assertFalse(DohPresets.ALL.any { it.id == DohPresets.CUSTOM_ID })
    }

    @Test
    fun `custom URLs must be https with a host`() {
        assertTrue(DohPresets.isValidCustomUrl("https://dns.nextdns.io/abc123"))
        assertTrue(DohPresets.isValidCustomUrl("  https://doh.example/dns-query  "))
        assertFalse("cleartext would leak every lookup", DohPresets.isValidCustomUrl("http://dns.example/dns-query"))
        assertFalse(DohPresets.isValidCustomUrl("dns.example"))
        assertFalse(DohPresets.isValidCustomUrl(""))
        assertFalse(DohPresets.isValidCustomUrl("https://"))
    }
}
