package com.fauxx

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.data.crawllist.DomainBlocklist
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DomainBlocklistTest {

    private fun createBlocklist(json: String? = null, throwOnOpen: Boolean = false): DomainBlocklist {
        val assets: AssetManager = mockk()
        val context: Context = mockk()
        every { context.assets } returns assets

        if (throwOnOpen) {
            every { assets.open("blocklist.json") } throws IOException("Asset not found")
        } else if (json != null) {
            every { assets.open("blocklist.json") } returns ByteArrayInputStream(json.toByteArray())
        }

        return DomainBlocklist(context)
    }

    @Test
    fun `blocks domains from loaded blocklist`() {
        val blocklist = createBlocklist(
            """{"domains": ["evil.com", "bad.org"], "patterns": []}"""
        )
        assertFalse("loadFailed should be false for valid blocklist", blocklist.loadFailed)
        assertTrue("Should block listed domain", blocklist.isBlocked("evil.com"))
        assertTrue("Should block subdomain", blocklist.isBlocked("sub.evil.com"))
        assertFalse("Should not block unlisted domain", blocklist.isBlocked("good.com"))
    }

    @Test
    fun `fails closed when blocklist json cannot be loaded`() {
        val blocklist = createBlocklist(throwOnOpen = true)
        assertTrue("loadFailed should be true when file missing", blocklist.loadFailed)
        assertTrue("Should block ALL hosts when loadFailed", blocklist.isBlocked("google.com"))
        assertTrue("Should block ALL hosts when loadFailed", blocklist.isBlocked("example.org"))
        assertTrue("isUrlBlocked should also fail closed", blocklist.isUrlBlocked("https://safe.com/page"))
    }

    @Test
    fun `fails closed when blocklist json is empty`() {
        val blocklist = createBlocklist("""{"domains": [], "patterns": []}""")
        assertTrue("loadFailed should be true when blocklist is empty", blocklist.loadFailed)
        assertTrue("Should block ALL hosts when empty", blocklist.isBlocked("anything.com"))
    }

    @Test
    fun `does not fail when blocklist has content`() {
        val blocklist = createBlocklist(
            """{"domains": ["blocked.com"], "patterns": ["malware\\."]}"""
        )
        assertFalse("loadFailed should be false", blocklist.loadFailed)
        assertFalse("Should allow safe domain", blocklist.isBlocked("safe.com"))
        assertTrue("Should block matched domain", blocklist.isBlocked("blocked.com"))
        assertTrue("Should block pattern match", blocklist.isBlocked("malware.example.com"))
    }
}
