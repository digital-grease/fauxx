package com.fauxx.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards the log-export share path against issue #239: a multi-megabyte export must ride
 * [Intent.EXTRA_STREAM] as a FileProvider URI, never [Intent.EXTRA_TEXT], or `startActivity`
 * serializes the whole string into the ~1MB Binder transaction and crashes with
 * `TransactionTooLargeException`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FileShareTest {

    @Test
    fun `writeToCache writes the content to a cache file`() {
        val context = RuntimeEnvironment.getApplication()
        val file = FileShare.writeToCache(context.cacheDir, "action_log.html", "hello-body")
        assertTrue(file.exists())
        assertEquals("hello-body", file.readText())
    }

    @Test
    fun `buildSendIntent puts the uri in EXTRA_STREAM and only the caption in EXTRA_TEXT`() {
        val uri = Uri.parse("content://com.fauxx.fileprovider/cache/action_log.html")
        val intent = FileShare.buildSendIntent(uri, "text/html", "action_log.html", "Fauxx action log")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/html", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("Fauxx action log", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(
            "read permission must be granted to the receiving app",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Test
    fun `sharing a multi-megabyte log keeps the payload out of the intent extras`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val big = "X".repeat(2_000_000)

        val shared = FileShare.share(
            context = activity,
            content = big,
            mimeType = "text/html",
            fileName = "action_log.html",
            chooserTitle = "Share",
            caption = "Fauxx action log",
        )

        assertTrue("share should succeed when a handler exists", shared)

        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("an activity should have been started", chooser)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: chooser

        assertEquals(Intent.ACTION_SEND, send.action)
        assertNotNull("the payload must ride EXTRA_STREAM", send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertFalse(
            "the megabyte payload must never be placed in EXTRA_TEXT (issue #239)",
            send.getStringExtra(Intent.EXTRA_TEXT)?.contains(big) == true,
        )
    }
}
