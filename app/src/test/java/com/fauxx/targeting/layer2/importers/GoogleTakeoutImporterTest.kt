package com.fauxx.targeting.layer2.importers

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.CategoryMapper
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Direct tests for [GoogleTakeoutImporter]. Mocks `ContentResolver.openInputStream` so we
 * never hit the real SAF — the importer's job is to take whatever stream it's handed and
 * parse it, so this surfaces every parsing branch without device dependence.
 */
class GoogleTakeoutImporterTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var assetManager: AssetManager
    private lateinit var dao: PlatformProfileDao
    private lateinit var clock: Clock
    private lateinit var importer: GoogleTakeoutImporter

    private val fakeUri: Uri = mockk(relaxed = true)
    private val nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = mockk()
        contentResolver = mockk()
        assetManager = mockk()
        dao = mockk(relaxed = true)
        clock = mockk { every { currentTimeMillis() } returns nowMs }
        every { context.contentResolver } returns contentResolver
        every { context.assets } returns assetManager
        // CategoryMapper reads platform_category_map.json from assets on first use.
        // Hand it a small fixture so common topics resolve deterministically.
        every { assetManager.open("platform_category_map.json") } returns ByteArrayInputStream(
            """
            {
              "Video Games": "GAMING",
              "Travel": "TRAVEL",
              "Cooking": "COOKING"
            }
            """.trimIndent().toByteArray()
        )
        val mapper = CategoryMapper(context)
        importer = GoogleTakeoutImporter(context, mapper, dao, mockk(relaxed = true), clock)
    }

    @Test
    fun `MyAdCenter interests-category shape succeeds and persists mapped categories`() = runBlocking {
        val json = """
            {
              "interests": [
                {"category": "Video Games"},
                {"category": "Travel"},
                {"category": "Cooking"}
              ]
            }
        """.trimIndent()
        stubStream(json.toByteArray())
        val cacheSlot = slot<PlatformProfileCache>()
        coEvery { dao.upsert(capture(cacheSlot)) } returns Unit

        val result = importer.import(fakeUri)

        assertTrue("expected Success, got $result", result is ImportResult.Success)
        assertEquals(3, (result as ImportResult.Success).categoryCount)
        assertEquals("google", cacheSlot.captured.platformName)
        assertEquals(nowMs, cacheSlot.captured.lastScraped)
        // Cache stores CategoryPool name strings, not raw platform strings.
        val cached = cacheSlot.captured.scrapedCategoriesJson
        assertTrue(cached.contains(CategoryPool.GAMING.name))
        assertTrue(cached.contains(CategoryPool.TRAVEL.name))
        assertTrue(cached.contains(CategoryPool.COOKING.name))
    }

    @Test
    fun `MyAdCenter interests-name shape succeeds`() = runBlocking {
        val json = """
            {"interests": [{"name": "Video Games"}, {"name": "Travel"}]}
        """.trimIndent()
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertTrue(result is ImportResult.Success)
        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `MyAdCenter advertisingTopics flat-string shape succeeds`() = runBlocking {
        val json = """
            {"advertisingTopics": ["Video Games", "Travel"]}
        """.trimIndent()
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `root JsonArray of category objects also parses (pre-wrapper shape)`() = runBlocking {
        val json = """[{"category": "Video Games"}, {"category": "Travel"}]"""
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `MyActivity Ads fallback shape succeeds`() = runBlocking {
        // The MyActivity shape is an array of activity records; ad-targeting topics live
        // in details[] entries where name == "Ad Targeting".
        val json = """
            [
              {
                "header": "Ads",
                "title": "Saw an ad",
                "details": [
                  {"name": "Ad Targeting", "title": "Video Games"},
                  {"name": "Ad Targeting", "title": "Travel"},
                  {"name": "Some Other Field", "title": "ignore-me"}
                ]
              },
              {
                "header": "Ads",
                "title": "Saw another ad",
                "details": [{"name": "Ad Targeting", "title": "Cooking"}]
              }
            ]
        """.trimIndent()
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(3, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `empty interests array succeeds with count 0 and persists empty cache`() = runBlocking {
        val json = """{"interests": []}"""
        stubStream(json.toByteArray())
        val cacheSlot = slot<PlatformProfileCache>()
        coEvery { dao.upsert(capture(cacheSlot)) } returns Unit

        val result = importer.import(fakeUri)

        assertTrue("expected Success(0), got $result", result is ImportResult.Success)
        assertEquals(0, (result as ImportResult.Success).categoryCount)
        assertEquals("[]", cacheSlot.captured.scrapedCategoriesJson)
    }

    @Test
    fun `unrecognised JSON shape returns WrongFormat`() = runBlocking {
        val json = """{"completely_unrelated": "structure"}"""
        stubStream(json.toByteArray())

        val result = importer.import(fakeUri)

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        // dao must NOT have been called — we don't overwrite the cache with junk.
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `malformed JSON returns WrongFormat (not crash)`() = runBlocking {
        // JsonParser silently returns JsonNull on broken input, which all parser branches
        // reject — so the caller sees WrongFormat, never a crash. Documents the behavior.
        stubStream("not even close to JSON".toByteArray())

        val result = importer.import(fakeUri)

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `null InputStream from ContentResolver returns IoError`() = runBlocking {
        every { contentResolver.openInputStream(fakeUri) } returns null

        val result = importer.import(fakeUri)

        assertTrue("expected IoError, got $result", result is ImportResult.IoError)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `ZIP archive containing MyAdCenter at the standard Takeout path is parsed`() = runBlocking {
        val zipBytes = buildZip(
            mapOf(
                // Mirror the standard Takeout layout — including some non-target entries to
                // make sure those don't trip the parser. Real Takeouts include README.html,
                // archive_browser.html, and a maps directory among other things.
                "Takeout/archive_browser.html" to "<html>noise</html>",
                "Takeout/My Ad Center/MyAdCenter.json" to
                    """{"interests": [{"category": "Video Games"}, {"category": "Travel"}]}""",
                "Takeout/Maps/timeline-2024.json" to "[]"
            )
        )
        stubStream(zipBytes)
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `ZIP archive with only MyActivity fallback path is parsed`() = runBlocking {
        val zipBytes = buildZip(
            mapOf(
                "Takeout/My Activity/Ads/MyActivity.json" to
                    """[{"details": [{"name": "Ad Targeting", "title": "Cooking"}]}]"""
            )
        )
        stubStream(zipBytes)
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(1, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `ZIP with no recognised entries returns WrongFormat`() = runBlocking {
        val zipBytes = buildZip(
            mapOf(
                "Takeout/Maps/timeline.json" to "[]",
                "Takeout/Calendar/events.ics" to "BEGIN:VCALENDAR"
            )
        )
        stubStream(zipBytes)

        val result = importer.import(fakeUri)

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    private fun stubStream(bytes: ByteArray) {
        // Return a fresh stream each call so the test isn't accidentally reading from a
        // closed stream if the SUT reads twice (which it shouldn't, but defensive).
        every { contentResolver.openInputStream(fakeUri) } answers {
            ByteArrayInputStream(bytes)
        }
    }

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, contents) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
