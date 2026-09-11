package com.fauxx.service

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metered-Wi-Fi pause (issue #288) resumes on an `UNMETERED` constraint, which on a
 * permanently metered home network may never fire. This notice is the only thing that tells
 * the user why the app went quiet without them opening it, so its content is worth pinning.
 */
class MeteredWifiNoticeTest {

    @Test
    fun `the notice names the cause and the way out`() {
        val text = meteredWifiNoticeText()
        assertTrue("should name the cause: $text", text.contains("metered", ignoreCase = true))
        assertTrue("should point at the fix: $text", text.contains("mobile-data", ignoreCase = true))
    }

    @Test
    fun `the notice fits a notification without being truncated to uselessness`() {
        // Collapsed notification text is clipped around ~60 chars; the first clause has to
        // carry the meaning on its own.
        val text = meteredWifiNoticeText()
        assertTrue("first clause too long to survive collapsing: $text", text.take(60).contains("metered"))
    }
}
