package com.fauxx

import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.network.HeaderRandomizerInterceptor
import com.fauxx.network.UserAgentPool
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-cutting tests for the locale plumbing: switching the [LocaleManager.currentLocale]
 * value changes the headers and URL params produced downstream.
 *
 * Doesn't actually open network connections — uses an OkHttp interceptor chain stub
 * that records the request and returns a synthetic 200.
 */
class LocaleIntegrationTest {

    private fun fakeChain(captured: MutableList<Request>): Interceptor.Chain =
        mockk(relaxed = true) {
            val req = Request.Builder().url("https://example.test/").get().build()
            every { request() } returns req
            every { proceed(any()) } answers {
                val r = firstArg<Request>()
                captured.add(r)
                Response.Builder()
                    .request(r)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        }

    private fun ua(): UserAgentPool = mockk(relaxed = true) {
        every { random() } returns "Mozilla/5.0 (Linux; Android 14)"
    }

    private fun localeManager(initial: SupportedLocale): LocaleManager = mockk(relaxed = true) {
        every { currentLocale } returns initial
    }

    @Test
    fun `header randomizer emits Spanish primary when locale is es`() {
        val captured = mutableListOf<Request>()
        val interceptor = HeaderRandomizerInterceptor(ua(), localeManager(SupportedLocale.ES))
        repeat(20) { interceptor.intercept(fakeChain(captured)) }

        val acceptLanguages = captured.map { it.header("Accept-Language") ?: "" }
        assertTrue(
            "All emissions must have a Spanish primary tag — got $acceptLanguages",
            acceptLanguages.all { it.startsWith("es-") || it.startsWith("es,") }
        )
        assertTrue(
            "Should never emit en as primary in Spanish locale",
            acceptLanguages.none { it.startsWith("en-") || it.startsWith("en,") }
        )
    }

    @Test
    fun `header randomizer emits French primary when locale is fr`() {
        val captured = mutableListOf<Request>()
        val interceptor = HeaderRandomizerInterceptor(ua(), localeManager(SupportedLocale.FR))
        repeat(20) { interceptor.intercept(fakeChain(captured)) }

        val acceptLanguages = captured.map { it.header("Accept-Language") ?: "" }
        assertTrue(
            "All emissions must have a French primary tag — got $acceptLanguages",
            acceptLanguages.all { it.startsWith("fr-") || it.startsWith("fr,") }
        )
        assertTrue(
            "Should never emit en as primary in French locale",
            acceptLanguages.none { it.startsWith("en-") || it.startsWith("en,") }
        )
    }

    @Test
    fun `header randomizer emits Russian primary when locale is ru`() {
        val captured = mutableListOf<Request>()
        val interceptor = HeaderRandomizerInterceptor(ua(), localeManager(SupportedLocale.RU))
        repeat(20) { interceptor.intercept(fakeChain(captured)) }

        val acceptLanguages = captured.map { it.header("Accept-Language") ?: "" }
        assertTrue(
            "All emissions must have a Russian primary tag — got $acceptLanguages",
            acceptLanguages.all { it.startsWith("ru-") || it.startsWith("ru,") }
        )
        assertTrue(
            "Should never emit en as primary in Russian locale",
            acceptLanguages.none { it.startsWith("en-") || it.startsWith("en,") }
        )
    }

    @Test
    fun `header randomizer emits English primary when locale is en`() {
        val captured = mutableListOf<Request>()
        val interceptor = HeaderRandomizerInterceptor(ua(), localeManager(SupportedLocale.EN))
        repeat(20) { interceptor.intercept(fakeChain(captured)) }

        val acceptLanguages = captured.map { it.header("Accept-Language") ?: "" }
        assertTrue(
            "All emissions must have an English primary tag — got $acceptLanguages",
            acceptLanguages.all { it.startsWith("en-") || it.startsWith("en,") }
        )
    }

    @Test
    fun `SupportedLocale carries correct region and yahoo subdomain mapping`() {
        // Ensure the en/es/fr mappings match what SearchPoisonModule's URL builders rely on.
        assertEquals("US", SupportedLocale.EN.defaultRegion)
        assertEquals("ES", SupportedLocale.ES.defaultRegion)
        assertEquals("FR", SupportedLocale.FR.defaultRegion)
        assertEquals("RU", SupportedLocale.RU.defaultRegion)

        assertEquals("", SupportedLocale.EN.yahooSubdomainPrefix)
        assertEquals("es.", SupportedLocale.ES.yahooSubdomainPrefix)
        assertEquals("fr.", SupportedLocale.FR.yahooSubdomainPrefix)
        assertEquals("ru.", SupportedLocale.RU.yahooSubdomainPrefix)
    }

    @Test
    fun `SupportedLocale fromTag falls back to EN for unsupported language`() {
        assertEquals(SupportedLocale.EN, SupportedLocale.fromTag("de"))
        assertEquals(SupportedLocale.EN, SupportedLocale.fromTag("zh-CN"))
        assertEquals(SupportedLocale.EN, SupportedLocale.fromTag(null))
        assertEquals(SupportedLocale.EN, SupportedLocale.fromTag(""))
        assertEquals(SupportedLocale.ES, SupportedLocale.fromTag("es-MX"))
        assertEquals(SupportedLocale.FR, SupportedLocale.fromTag("fr-CA"))
        assertEquals(SupportedLocale.RU, SupportedLocale.fromTag("ru-RU"))
    }

    @Test
    fun `BuildConfig SHIPPED_LOCALES has at least one entry`() {
        val shipped = com.fauxx.BuildConfig.SHIPPED_LOCALES
        assertNotNull(shipped)
        assertTrue("SHIPPED_LOCALES must include at least 'en'", "en" in shipped)
        assertTrue("Debug SHIPPED_LOCALES must include 'ru'", "ru" in shipped)
    }
}
