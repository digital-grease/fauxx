package com.fauxx.engine.modules

import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.PoisonProfileRepository
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
import java.io.IOException

/**
 * [LocationSignalModule.onAction] is the Play-safe location-poisoning path: it picks a random
 * city, builds a location-flavored search query, dispatches ONE HTTP request, and logs a
 * SEARCH_QUERY entry recording the city's region. Unlike [SearchPoisonModule] there is NO
 * blocklist gate here — every generated query is dispatched.
 *
 * Plain JVM: only OkHttp + java.net.URLEncoder are exercised (no android.* framework), so no
 * Robolectric runner. The 20-template / 4-engine choice is pinned with a deterministic anonymous
 * [kotlin.random.Random] (nextBits()=0 -> first element of each .random(random) list), so the
 * test never depends on a real RNG draw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSignalModuleTest {

    private val cityDatabase: CityDatabase = mockk(relaxed = true)
    private val queryBankManager: QueryBankManager = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)
    private val httpClient: OkHttpClient = mockk(relaxed = true)

    // nextBits()=0 keeps LOCATION_QUERY_TEMPLATES.random(this) and SEARCH_ENGINES.random(this)
    // deterministic (first element) and terminating. nextFloat() is overridden defensively even
    // though this module never branches on it, mirroring the SearchPoison template's Random.
    private val deterministicRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.0f
    }

    private val knownCity = CityCoord(
        name = "Reykjavik",
        lat = 64.1466,
        lng = -21.9426,
        region = "IS_CAPITAL"
    )

    private fun newModule() = LocationSignalModule(
        cityDatabase = cityDatabase,
        queryBankManager = queryBankManager,
        profileRepo = profileRepo,
        httpClient = httpClient,
        random = deterministicRandom,
    )

    // (i) Happy path: exactly one request dispatched; the log records the city's region.
    @Test
    fun `onAction dispatches exactly one request and records the city region`() = runTest {
        every { cityDatabase.randomCity(any()) } returns knownCity

        val response: Response = mockk(relaxed = true)
        val call: Call = mockk(relaxed = true)
        every { call.execute() } returns response
        every { httpClient.newCall(any<Request>()) } returns call

        val result = newModule().onAction(CategoryPool.TRAVEL)

        verify(exactly = 1) { httpClient.newCall(any()) }
        verify(exactly = 1) { call.execute() }
        assertEquals(
            "location signal must log as a SEARCH_QUERY action",
            ActionType.SEARCH_QUERY,
            result.actionType
        )
        assertTrue(
            "detail must announce the location signal; was: ${result.detail}",
            result.detail.contains("Location signal:")
        )
        assertTrue(
            "detail must carry the chosen city's region; was: ${result.detail}",
            result.detail.contains(knownCity.region)
        )
    }

    // (ii) Network failure is swallowed (try/catch) and the SEARCH_QUERY entry is still returned.
    @Test
    fun `onAction swallows network failure and still returns a SEARCH_QUERY entry`() = runTest {
        every { cityDatabase.randomCity(any()) } returns knownCity

        val call: Call = mockk(relaxed = true)
        every { call.execute() } throws IOException("connection reset")
        every { httpClient.newCall(any<Request>()) } returns call

        val result = newModule().onAction(CategoryPool.TRAVEL)

        // The request was attempted exactly once even though it blew up.
        verify(exactly = 1) { httpClient.newCall(any()) }
        verify(exactly = 1) { call.execute() }
        assertEquals(
            "a thrown request must NOT change the logged action type",
            ActionType.SEARCH_QUERY,
            result.actionType
        )
        assertTrue(
            "detail must still record the location signal after a swallowed failure; was: ${result.detail}",
            result.detail.contains("Location signal:") && result.detail.contains(knownCity.region)
        )
    }
}
