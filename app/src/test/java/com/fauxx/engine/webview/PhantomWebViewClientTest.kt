package com.fauxx.engine.webview

import android.net.Uri
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [PhantomWebViewClient] — the WebViewClient guarding background
 * Fauxx WebViews. Verifies the request-interception (blocklist + Accept-header MIME
 * gate), navigation override, SSL/Safe-Browsing fail-closed handling, and the
 * onPageFinished callback wiring.
 *
 * onPageStarted JS-injection and the WebSettings lockdown are intentionally excluded:
 * Robolectric's ShadowWebView cannot model JS execution or load the application, so
 * those are covered by the instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhantomWebViewClientTest {

    private val blocklist: DomainBlocklist = mockk()

    // The WebView is never dereferenced by any method under test; relaxed so any
    // incidental call is a no-op.
    private val view: WebView = mockk(relaxed = true)

    private fun client(onPageFinished: ((String) -> Unit)? = null) =
        PhantomWebViewClient(blocklist, onPageFinished = onPageFinished)

    @Test
    fun `shouldInterceptRequest counts only allowed resource requests`() {
        val counter = AtomicInteger(0)
        val c = PhantomWebViewClient(blocklist, resourceCounter = counter)
        every { blocklist.isBlocked("ok.example") } returns false
        assertNull(c.shouldInterceptRequest(view, request("https://ok.example/app.js")))
        assertEquals("an allowed request increments the counter", 1, counter.get())
        every { blocklist.isBlocked("evil.com") } returns true
        assertNotNull(c.shouldInterceptRequest(view, request("https://evil.com/track.js")))
        assertEquals("a blocked request must not be counted", 1, counter.get())
    }

    /** A WebResourceRequest whose url and headers are fully mocked. */
    private fun request(url: String, headers: Map<String, String> = emptyMap()): WebResourceRequest {
        val req: WebResourceRequest = mockk()
        every { req.url } returns Uri.parse(url)
        every { req.requestHeaders } returns headers
        return req
    }

    // --- shouldInterceptRequest ------------------------------------------------

    @Test
    fun `shouldInterceptRequest returns a stub for a blocklisted host`() {
        every { blocklist.isBlocked("evil.com") } returns true
        val req = request("https://evil.com/tracker.js")

        val response = client().shouldInterceptRequest(view, req)

        assertNotNull("blocklisted host must be intercepted", response)
        assertEquals("text/plain", response!!.mimeType)
        assertEquals("utf-8", response.encoding)
        assertNull("intercept stub carries no body", response.data)
    }

    @Test
    fun `shouldInterceptRequest returns null for an allowed host requesting html`() {
        every { blocklist.isBlocked(any()) } returns false
        val req = request("https://good.com/index.html", mapOf("Accept" to "text/html"))

        val response = client().shouldInterceptRequest(view, req)

        assertNull("allowed html request must pass through", response)
    }

    @Test
    fun `shouldInterceptRequest returns null when the url has no host`() {
        every { blocklist.isBlocked(any()) } returns false
        // Robolectric's Uri.parse maps some opaque/relative forms to an empty host ("")
        // rather than null, so mock the Uri's host explicitly to pin the null-host guard.
        val req: WebResourceRequest = mockk()
        val uri: Uri = mockk()
        every { uri.host } returns null
        every { req.url } returns uri
        every { req.requestHeaders } returns emptyMap()

        assertNull("precondition: hostless Uri must have null host", req.url.host)

        val response = client().shouldInterceptRequest(view, req)

        assertNull("a request with no host must not be intercepted", response)
    }

    @Test
    fun `shouldInterceptRequest blocks an application pdf accept header`() {
        every { blocklist.isBlocked(any()) } returns false
        // NOTE: this matches the REQUEST's Accept header, not a response Content-Type.
        // The MIME gate looks at what the page is asking to receive.
        val req = request("https://good.com/report", mapOf("Accept" to "application/pdf"))

        val response = client().shouldInterceptRequest(view, req)

        assertNotNull("a pdf-accepting request must be intercepted", response)
        assertEquals("text/plain", response!!.mimeType)
        assertEquals("utf-8", response.encoding)
        assertNull(response.data)
    }

    @Test
    fun `shouldInterceptRequest allows a request with no accept header`() {
        every { blocklist.isBlocked(any()) } returns false
        // Empty headers -> Accept defaults to "" -> no blocked MIME substring matches.
        val req = request("https://good.com/page")

        val response = client().shouldInterceptRequest(view, req)

        assertNull("absent Accept header must not trigger the MIME gate", response)
    }

    // --- shouldOverrideUrlLoading ----------------------------------------------

    @Test
    fun `shouldOverrideUrlLoading returns true for a blocklisted host`() {
        every { blocklist.isBlocked("evil.com") } returns true
        val req = request("https://evil.com/login")

        assertTrue(
            "navigation to a blocklisted host must be overridden (blocked)",
            client().shouldOverrideUrlLoading(view, req)
        )
    }

    @Test
    fun `shouldOverrideUrlLoading returns false for an allowed host`() {
        every { blocklist.isBlocked(any()) } returns false
        val req = request("https://good.com/next")

        assertFalse(
            "navigation to an allowed host must proceed",
            client().shouldOverrideUrlLoading(view, req)
        )
    }

    @Test
    fun `shouldOverrideUrlLoading returns true when the url has no host`() {
        every { blocklist.isBlocked(any()) } returns false
        // Intentional asymmetry vs shouldInterceptRequest: a hostless navigation is
        // overridden (true), whereas a hostless intercept is allowed through (null).
        val req = request("about:blank")

        assertNull("precondition: hostless Uri must have null host", req.url.host)
        assertTrue(
            "a hostless navigation must be overridden (intentional asymmetry vs intercept)",
            client().shouldOverrideUrlLoading(view, req)
        )
    }

    // --- onReceivedSslError ----------------------------------------------------

    @Test
    fun `onReceivedSslError always cancels and never proceeds`() {
        val handler: SslErrorHandler = mockk(relaxed = true)
        val error: SslError = mockk(relaxed = true)

        client().onReceivedSslError(view, handler, error)

        verify(exactly = 1) { handler.cancel() }
        verify(exactly = 0) { handler.proceed() }
    }

    // --- onSafeBrowsingHit -----------------------------------------------------

    @Test
    fun `onSafeBrowsingHit backs to safety without a report`() {
        val callback: SafeBrowsingResponse = mockk(relaxed = true)
        val req: WebResourceRequest = mockk(relaxed = true)

        client().onSafeBrowsingHit(view, req, /* threatType */ 1, callback)

        verify(exactly = 1) { callback.backToSafety(false) }
    }

    // --- onRenderProcessGone (issue #210) --------------------------------------

    @Test
    fun `onRenderProcessGone returns true so Android does not kill the app process`() {
        var notified: WebView? = null
        val client = PhantomWebViewClient(blocklist, onRenderGone = { notified = it })
        val detail: RenderProcessGoneDetail = mockk(relaxed = true)
        every { detail.didCrash() } returns true

        val handled = client.onRenderProcessGone(view, detail)

        assertTrue("must return true to prevent the app-process kill", handled)
        assertEquals("the dead WebView must be handed to the pool for replacement", view, notified)
    }

    @Test
    fun `onRenderProcessGone still returns true with no callback wired`() {
        val client = PhantomWebViewClient(blocklist)
        val detail: RenderProcessGoneDetail = mockk(relaxed = true)
        every { detail.didCrash() } returns false

        assertTrue(client.onRenderProcessGone(view, detail))
    }

    // --- onPageFinished --------------------------------------------------------

    @Test
    fun `onPageFinished invokes the supplied callback with the url`() {
        var captured: String? = null
        val client = client(onPageFinished = { captured = it })

        client.onPageFinished(view, "https://good.com/done")

        assertEquals("the callback must receive the finished url", "https://good.com/done", captured)
    }
}
