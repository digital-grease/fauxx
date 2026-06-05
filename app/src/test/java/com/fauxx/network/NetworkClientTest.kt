package com.fauxx.network

import android.content.Context
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.support.seededRandom
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
import java.io.ByteArrayInputStream
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

    /**
     * A test pool whose UA list comes from the (mocked) asset stream rather than from a
     * stubbed [UserAgentPool.random]. Two distinct UAs in the asset, customUserAgent = null,
     * so [UserAgentPool.random] samples the pool (no override). Seeded [Random] makes the
     * rotation reproducible across runs.
     */
    private fun rotatingUaPool(vararg agents: String): UserAgentPool {
        val json = agents.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }.toByteArray()
        val context: Context = mockk {
            every { assets } returns mockk {
                every { open("user_agents.json") } answers { ByteArrayInputStream(json) }
            }
        }
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns PoisonProfile(customUserAgent = null)
        }
        return UserAgentPool(context, profileRepo, seededRandom())
    }

    @Test
    fun `User-Agent rotates across the pool over the wire`() {
        // REAL pool (2 UAs from the mocked asset) + REAL interceptor, both seam-injected
        // with a seeded Random so the rotation is deterministic but still varies per call.
        val pool = setOf("UA-Alpha/1.0", "UA-Bravo/2.0")
        val interceptor = HeaderRandomizerInterceptor(
            uaPool = rotatingUaPool(*pool.toTypedArray()),
            localeManager = localeManager(SupportedLocale.EN),
            random = seededRandom(),
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        val requestCount = 30
        repeat(requestCount) { server.enqueue(MockResponse().setResponseCode(200).setBody("ok")) }
        repeat(requestCount) {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { resp ->
                assertTrue(resp.isSuccessful)
            }
        }

        val observed = (0 until requestCount)
            .map { server.takeRequest().getHeader("User-Agent") }
            .toSet()
        assertTrue(
            "every observed UA must come from the pool; saw: $observed",
            pool.containsAll(observed)
        )
        assertTrue(
            "UA must rotate across the pool (more than one distinct value), not stick; saw: $observed",
            observed.size > 1
        )
    }

    @Test
    fun `Accept-Language emits the locale primary tag for EN FR and RU over the wire`() {
        // Guards the RU-fallback regression: without a RU table the interceptor used to fall
        // back to the EN variants and emit en-* for a Russian install. The ES case is already
        // covered above; this locks the other three primary tags on the wire.
        //
        // The interceptor picks one variant per request via variants.random(). Some pools
        // mix region forms (e.g. "ru-RU,..." and "ru,en;q=0.7"), so we assert on the PRIMARY
        // language subtag (the part before the first '-' or ',') rather than a literal "ru-"
        // prefix — that's region-form-agnostic but still catches the en-fallback regression.
        val cases = mapOf(
            SupportedLocale.EN to "en",
            SupportedLocale.FR to "fr",
            SupportedLocale.RU to "ru",
        )
        for ((locale, expectedLang) in cases) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            client(locale).newCall(Request.Builder().url(server.url("/")).build())
                .execute().use { resp -> assertTrue(resp.isSuccessful) }

            val acceptLanguage = server.takeRequest().getHeader("Accept-Language") ?: ""
            val primaryLang = acceptLanguage.substringBefore(',').substringBefore('-')
            assertEquals(
                "$locale must emit the $expectedLang primary tag on the wire, got: $acceptLanguage",
                expectedLang,
                primaryLang
            )
            assertTrue(
                "$locale must emit a $expectedLang- prefixed primary token, got: $acceptLanguage",
                acceptLanguage.startsWith("$expectedLang-") || acceptLanguage.startsWith("$expectedLang,")
            )
        }
    }
}
