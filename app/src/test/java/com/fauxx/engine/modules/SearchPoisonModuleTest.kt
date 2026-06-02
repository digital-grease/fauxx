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
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chokepoint #4: final dispatch gate in [SearchPoisonModule.onAction].
 *
 * The gate is a user-safety invariant: a query that matches [QueryBlocklist] (or is blank)
 * must be DROPPED, never dispatched over the network. A dropped action is cheaper than a
 * false user-signal (e.g. a 988 crisis-line query that could trigger a welfare check).
 *
 * Plain JVM: only OkHttp + java.net.URLEncoder are exercised (no android.* framework), so no
 * Robolectric runner. The 60/40 markov-vs-bank branch is made irrelevant by stubbing BOTH
 * generators to the SAME controlled string; the gate's behavior is identical on either branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPoisonModuleTest {

    private val queryBankManager: QueryBankManager = mockk(relaxed = true)
    private val markovGenerator: MarkovQueryGenerator = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)
    private val httpClient: OkHttpClient = mockk(relaxed = true)
    private val demographicDao: DemographicProfileDao = mockk(relaxed = true)
    private val customInterestMapper: CustomInterestMapper = mockk(relaxed = true)
    private val queryBlocklist: QueryBlocklist = mockk(relaxed = true)
    private val localeManager: LocaleManager = mockk(relaxed = true)

    private fun newModule() = SearchPoisonModule(
        queryBankManager = queryBankManager,
        markovGenerator = markovGenerator,
        profileRepo = profileRepo,
        httpClient = httpClient,
        demographicDao = demographicDao,
        customInterestMapper = customInterestMapper,
        queryBlocklist = queryBlocklist,
        localeManager = localeManager,
        // Force the query-bank branch: nextFloat() >= 0.60 so onAction never calls
        // markovGenerator.generate(category). Routing that call through a mock is unsafe —
        // its default targetLength argument (random.nextInt(3,9)) dereferences the
        // generator's own `random`, which is null on a MockK mock and NPEs inside the
        // synthetic generate$default. The gate's behavior is identical on either branch.
        random = bankBranchRandom,
    )

    // nextFloat()=0.99 forces the bank branch; nextBits()=0 keeps SEARCH_ENGINES.random(this)
    // deterministic (first engine) and terminating.
    private val bankBranchRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.99f
    }

    /** Stub the query-bank source (the forced branch) to [query]. */
    private fun bankReturns(query: String) {
        every { queryBankManager.randomQuery(any()) } returns query
    }

    // (a) Blocklisted query -> [BLOCKED] drop, SEARCH_QUERY type, network NEVER touched.
    @Test
    fun `blocklisted query is dropped and never dispatched`() = runTest {
        bankReturns("how to make a pipe bomb")
        every { queryBlocklist.isBlocked(any()) } returns true
        every { localeManager.currentLocale } returns SupportedLocale.EN

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(
            "blocked query must log as a SEARCH_QUERY action",
            ActionType.SEARCH_QUERY,
            result.actionType
        )
        assertTrue(
            "detail must be flagged [BLOCKED]; was: ${result.detail}",
            result.detail.startsWith("[BLOCKED]")
        )
        // The safety invariant: a blocked query must never reach the network.
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    // (b) Blank/empty generated query -> same [BLOCKED] drop, no dispatch.
    @Test
    fun `blank query is dropped and never dispatched`() = runTest {
        bankReturns("")
        // Even with the blocklist clear, isBlank() short-circuits to the drop path.
        every { queryBlocklist.isBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertTrue(
            "blank query must be flagged [BLOCKED]; was: ${result.detail}",
            result.detail.startsWith("[BLOCKED]")
        )
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    // (c) Safe query, blocklist clear -> dispatched exactly once; detail carries query + engine.
    @Test
    fun `safe query is dispatched exactly once and detail records the engine`() = runTest {
        val safeQuery = "best mechanical keyboards 2026"
        bankReturns(safeQuery)
        every { queryBlocklist.isBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN

        // Mock the blocking OkHttp chain: newCall(req).execute().use { resp -> ... }.
        // Relaxed Response auto-stubs close() (so the use{} extension completes) and the
        // isSuccessful read inside the block.
        val response: Response = mockk(relaxed = true)
        val call: Call = mockk(relaxed = true)
        every { call.execute() } returns response
        every { httpClient.newCall(any<Request>()) } returns call

        val result = newModule().onAction(CategoryPool.GAMING)

        verify(exactly = 1) { httpClient.newCall(any()) }
        verify(exactly = 1) { call.execute() }
        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertTrue(
            "detail must contain the dispatched query; was: ${result.detail}",
            result.detail.contains(safeQuery)
        )
        assertTrue(
            "detail must carry the ' via ' engine suffix; was: ${result.detail}",
            result.detail.contains(" via ")
        )
    }
}
