package com.fauxx.network

import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Deterministic, no-live-network tests for the HTTP path: the [HeaderRandomizerInterceptor]
 * anti-fingerprint headers as they actually arrive on the wire, and the client's timeout /
 * 5xx behavior (mirroring NetworkModule.provideOkHttpClient). Uses a local MockWebServer.
 */
class NetworkClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun uaPool(): UserAgentPool = mockk {
        every { random() } returns "TestUA/1.0"
    }

    private fun localeManager(locale: SupportedLocale): LocaleManager = mockk(relaxed = true) {
        every { currentLocale } returns locale
    }

    /** A client built like NetworkModule.provideOkHttpClient, with a tunable read timeout. */
    private fun client(
        locale: SupportedLocale = SupportedLocale.ES,
        readTimeoutMs: Long = 30_000L,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HeaderRandomizerInterceptor(uaPool(), localeManager(locale)))
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    @Test
    fun `interceptor puts all five anti-fingerprint headers on the wire`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        client().newCall(Request.Builder().url(server.url("/")).build()).execute().use { resp ->
            assertTrue(resp.isSuccessful)
        }

        val recorded = server.takeRequest()
        assertEquals("UA must come from the pool", "TestUA/1.0", recorded.getHeader("User-Agent"))
        assertEquals("gzip, deflate, br", recorded.getHeader("Accept-Encoding"))
        assertEquals("1", recorded.getHeader("DNT"))
        assertNotNull("Accept header must be set", recorded.getHeader("Accept"))
        val acceptLanguage = recorded.getHeader("Accept-Language") ?: ""
        assertTrue(
            "ES locale must emit an es primary tag on the wire, got: $acceptLanguage",
            acceptLanguage.startsWith("es-") || acceptLanguage.startsWith("es,")
        )
    }

    @Test
    fun `a read timeout surfaces as SocketTimeoutException`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // Short read timeout to keep the test fast; production uses 30s (asserting behavior).
        val c = client(readTimeoutMs = 300L)
        try {
            c.newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("expected a SocketTimeoutException when the server never responds")
        } catch (_: SocketTimeoutException) {
            // expected
        }
    }

    @Test
    fun `a 5xx response is returned as unsuccessful, not thrown`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        client().newCall(Request.Builder().url(server.url("/")).build()).execute().use { resp ->
            assertFalse("5xx must not be treated as success", resp.isSuccessful)
            assertEquals(503, resp.code)
        }
    }
}
