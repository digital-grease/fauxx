package com.fauxx.engine.webview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.network.dns.HostResolver
import com.fauxx.network.dns.LoopbackProxy
import com.fauxx.network.dns.ProxyCredentials
import com.fauxx.network.dns.Resolution
import com.fauxx.network.dns.SystemHostResolver
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.Collections

/**
 * V1 for #227: a pooled WebView, pointed at the real [LoopbackProxy] through the real
 * [WebViewProxyOverride], loads pages whose names only Fauxx's resolver gets to answer.
 *
 * Proves, on a real WebView: the pool's own client answers the proxy's password challenge; every
 * hostname reaches Fauxx's resolver (including one the system resolver could never resolve); and a
 * page actually loads through the tunnel. Needs network access; skipped when offline.
 */
@RunWith(AndroidJUnit4::class)
class CustomDnsInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun webViewTrafficResolvesThroughFauxx() {
        // Assumptions outside runBlocking: thrown from inside a coroutine they are reported as a
        // failure instead of a skip.
        assumeTrue("no PROXY_OVERRIDE on this WebView", WebViewProxyOverride.SYSTEM.isSupported())
        val exampleIp = runCatching { InetAddress.getByName("example.com") }.getOrNull()
        assumeTrue("needs network access to reach example.com", exampleIp != null)
        runBlocking { check(exampleIp!!) }
    }

    private suspend fun check(exampleIp: InetAddress) {

        val asked = Collections.synchronizedList(mutableListOf<String>())
        val resolver = HostResolver { host ->
            asked += host
            when (host) {
                // The .invalid name can only resolve here: if it reaches us, Chromium did no lookup.
                "example.com", "fauxx-dns-test.invalid" -> Resolution.Addresses(listOf(exampleIp))
                else -> SystemHostResolver.resolve(host)
            }
        }
        val credentials = ProxyCredentials.random()
        val proxy = LoopbackProxy(resolver, credentials)
        val auth = object : PhantomProxyAuth {
            override fun credentialsFor(host: String, realm: String) =
                if (host == "127.0.0.1" && realm == credentials.realm) credentials.user to credentials.secret else null
        }
        val pool = PhantomWebViewPool(
            context, mockk<DomainBlocklist>(relaxed = true), PersonaJarStore(),
            PhantomIdentityProvider.NONE, PhantomBrowsingPrefs.NONE, auth,
        )
        try {
            assertTrue("WebView must accept the override", WebViewProxyOverride.SYSTEM.set(proxy.start()))
            pool.initialize()
            val webView = pool.acquire()
            try {
                instrumentation.runOnMainSync { webView.loadUrl("https://example.com/") }
                assertEquals("Example Domain", awaitTitle(webView, "Example Domain"))
                assertTrue("example.com must be resolved by Fauxx: $asked", "example.com" in asked)

                instrumentation.runOnMainSync { webView.loadUrl("https://fauxx-dns-test.invalid/") }
                awaitAsked(asked, "fauxx-dns-test.invalid")
                assertTrue(
                    "an unresolvable name must still reach Fauxx's resolver, so Chromium did no lookup: $asked",
                    "fauxx-dns-test.invalid" in asked,
                )
            } finally {
                pool.release(webView)
            }
        } finally {
            WebViewProxyOverride.SYSTEM.clear()
            pool.destroy()
            proxy.stop()
        }
    }

    private fun awaitTitle(webView: android.webkit.WebView, expected: String): String? {
        var title: String? = null
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { title = webView.title }
            if (title == expected) return title
            Thread.sleep(250)
        }
        return title
    }

    private fun awaitAsked(asked: List<String>, host: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && host !in asked) Thread.sleep(100)
    }
}
