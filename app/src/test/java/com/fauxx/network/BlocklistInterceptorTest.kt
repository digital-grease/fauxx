package com.fauxx.network

import com.fauxx.data.crawllist.DomainBlocklist
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Guardrail (issue #165): the OkHttp path must route every request through the
 * fail-closed [BlocklistInterceptor], so no module can reach a blocked host. The
 * WebView path is gated by PhantomWebViewClient and covered elsewhere.
 */
class BlocklistInterceptorTest {

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

    private fun clientWith(blocked: Boolean): OkHttpClient {
        val blocklist = mockk<DomainBlocklist> { every { isBlocked(any()) } returns blocked }
        return OkHttpClient.Builder().addInterceptor(BlocklistInterceptor(blocklist)).build()
    }

    @Test
    fun `allowed host proceeds`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        clientWith(blocked = false)
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute().use { resp -> assertTrue(resp.isSuccessful) }
    }

    @Test
    fun `blocked host is rejected before the request leaves`() {
        try {
            clientWith(blocked = true)
                .newCall(Request.Builder().url(server.url("/")).build())
                .execute()
            fail("expected an IOException for a blocked host")
        } catch (_: IOException) {
            // expected: the interceptor fails closed
        }
        // The request must never have reached the server.
        assertTrue("a blocked request must not hit the server", server.requestCount == 0)
    }

    @Test
    fun `blocklist load failure fails closed`() {
        // isBlocked returns true for ALL hosts when the blocklist could not load.
        val blocklist = mockk<DomainBlocklist> { every { isBlocked(any()) } returns true }
        val client = OkHttpClient.Builder().addInterceptor(BlocklistInterceptor(blocklist)).build()
        try {
            client.newCall(Request.Builder().url("https://example.com/").build()).execute()
            fail("expected an IOException when the blocklist has failed closed")
        } catch (_: IOException) {
            // expected
        }
    }
}
