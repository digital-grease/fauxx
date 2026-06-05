package com.fauxx.engine.modules

import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicProfileDao
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Wire-level companion to [SearchPoisonModuleTest]: instead of mocking the OkHttp call chain,
 * this proves a real request actually LEAVES [SearchPoisonModule] and is dispatched.
 *
 * The module builds absolute search-engine URLs (`https://www.google.com/search?...`). A
 * **test-only** URL-rewrite [Interceptor] rewrites the outgoing host:port to the local
 * [MockWebServer] (scheme/host/port swap, path + query preserved) so the request lands on the
 * server we can inspect — WITHOUT adding a baseUrl seam to production code. The real
 * locale-aware URL builder, blocklist gate, and OkHttp dispatch all run unchanged.
 *
 * Plain JVM (OkHttp + java.net.URLEncoder, no android.* framework) so no Robolectric runner,
 * matching [SearchPoisonModuleTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPoisonModuleWireTest {

    private lateinit var server: MockWebServer

    private val queryBankManager: QueryBankManager = mockk(relaxed = true)
    private val markovGenerator: MarkovQueryGenerator = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)
    private val demographicDao: DemographicProfileDao = mockk(relaxed = true)
    private val customInterestMapper: CustomInterestMapper = mockk(relaxed = true)
    private val queryBlocklist: QueryBlocklist = mockk(relaxed = true)
    private val localeManager: LocaleManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Test-only interceptor: rewrites the absolute search-engine URL the module emits so its
     * host:port points at the local [MockWebServer]. Path and query (the `?q=...&hl=...` the
     * module built) are preserved, so the request that arrives at the server is the real one.
     */
    private fun urlRewriteInterceptor(): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val rewrittenUrl = original.url.newBuilder()
            .scheme("http")
            .host(server.hostName)
            .port(server.port)
            .build()
        chain.proceed(original.newBuilder().url(rewrittenUrl).build())
    }

    /** A REAL OkHttpClient built like NetworkModule, plus the test-only URL rewrite. */
    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(urlRewriteInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun newModule(httpClient: OkHttpClient) = SearchPoisonModule(
        queryBankManager = queryBankManager,
        markovGenerator = markovGenerator,
        profileRepo = profileRepo,
        httpClient = httpClient,
        demographicDao = demographicDao,
        customInterestMapper = customInterestMapper,
        queryBlocklist = queryBlocklist,
        localeManager = localeManager,
        // nextFloat()=0.99 forces the query-bank branch (never calls markovGenerator.generate);
        // nextBits()=0 makes SEARCH_ENGINES.random(this) deterministic (first engine = google).
        random = bankBranchRandom,
    )

    // Mirrors SearchPoisonModuleTest.bankBranchRandom: query-bank branch + google engine.
    private val bankBranchRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.99f
    }

    /** Stub the forced query-bank source (and locale + clear blocklist) for a safe query. */
    private fun stubSafeQuery(query: String) {
        every { queryBankManager.randomQuery(any()) } returns query
        every { queryBlocklist.isBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN
    }

    @Test
    fun `safe query dispatches exactly one real request to the mock server`() = runTest {
        val safeQuery = "best mechanical keyboards 2026"
        stubSafeQuery(safeQuery)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = newModule(client()).onAction(CategoryPool.GAMING)

        // Exactly one request actually left the module and reached the server.
        assertEquals("exactly one request must reach the wire", 1, server.requestCount)
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("a request must have been dispatched to the mock server", recorded)

        // The encoded query and google's hl param survived the URL rewrite onto the wire.
        val path = recorded!!.path ?: ""
        assertTrue(
            "the dispatched URL must carry the encoded query on the wire; path was: $path",
            path.contains("best+mechanical+keyboards+2026") ||
                path.contains("best%20mechanical%20keyboards%202026")
        )

        // The returned log entry records the query and the google engine suffix.
        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertTrue(
            "detail must contain the dispatched query; was: ${result.detail}",
            result.detail.contains(safeQuery)
        )
        assertTrue(
            "detail must carry the google engine suffix; was: ${result.detail}",
            result.detail.contains("via google")
        )
    }

    @Test
    fun `a 503 from the search engine is swallowed and still logs the engine`() = runTest {
        // The module's try/catch + non-fatal !isSuccessful branch must not turn a 5xx into a
        // [BLOCKED] drop: the action is a best-effort poison hit, so a failed dispatch still
        // logs a normal SEARCH_QUERY entry with the engine suffix.
        val safeQuery = "best mechanical keyboards 2026"
        stubSafeQuery(safeQuery)
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val result = newModule(client()).onAction(CategoryPool.GAMING)

        assertEquals("the request still reached the wire", 1, server.requestCount)
        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertFalse(
            "a 5xx must NOT be turned into a [BLOCKED] drop; was: ${result.detail}",
            result.detail.startsWith("[BLOCKED]")
        )
        assertTrue(
            "detail must still carry the google engine suffix; was: ${result.detail}",
            result.detail.contains("via google")
        )
    }

    @Test
    fun `a dropped connection is swallowed and still logs the engine`() = runTest {
        // DISCONNECT_AFTER_REQUEST drops the socket once the request is read, surfacing as an
        // IOException inside the module's try/catch. The catch swallows it (Timber.w) and the
        // action still logs the engine — a transport failure is not a safety drop.
        val safeQuery = "best mechanical keyboards 2026"
        stubSafeQuery(safeQuery)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val result = newModule(client()).onAction(CategoryPool.GAMING)

        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertFalse(
            "a transport failure must NOT be turned into a [BLOCKED] drop; was: ${result.detail}",
            result.detail.startsWith("[BLOCKED]")
        )
        assertTrue(
            "detail must still carry the google engine suffix; was: ${result.detail}",
            result.detail.contains("via google")
        )
    }
}
