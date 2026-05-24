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

class FacebookDyiImporterTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var assetManager: AssetManager
    private lateinit var dao: PlatformProfileDao
    private lateinit var clock: Clock
    private lateinit var importer: FacebookDyiImporter

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
        importer = FacebookDyiImporter(context, mapper, dao, clock)
    }

    @Test
    fun `2024 label_values shape succeeds and persists mapped categories`() = runBlocking {
        val json = """
            {
              "label_values": [
                {
                  "label": "Other Categories Used to Reach You",
                  "vec": [
                    {"value": "Video Games"},
                    {"value": "Travel"},
                    {"value": "Cooking"}
                  ]
                }
              ]
            }
        """.trimIndent()
        stubStream(json.toByteArray())
        val cacheSlot = slot<PlatformProfileCache>()
        coEvery { dao.upsert(capture(cacheSlot)) } returns Unit

        val result = importer.import(fakeUri)

        assertTrue("expected Success, got $result", result is ImportResult.Success)
        assertEquals(3, (result as ImportResult.Success).categoryCount)
        assertEquals("facebook", cacheSlot.captured.platformName)
        assertEquals(nowMs, cacheSlot.captured.lastScraped)
        val cached = cacheSlot.captured.scrapedCategoriesJson
        assertTrue(cached.contains(CategoryPool.GAMING.name))
        assertTrue(cached.contains(CategoryPool.TRAVEL.name))
        assertTrue(cached.contains(CategoryPool.COOKING.name))
    }

    @Test
    fun `older ads_interests topics_v2 shape succeeds`() = runBlocking {
        val json = """{"topics_v2": ["Video Games", "Travel"]}"""
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `older ads_interests flat ads_interests-key shape succeeds`() = runBlocking {
        val json = """{"ads_interests": ["Cooking", "Travel"]}"""
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `root JsonArray of name objects also parses`() = runBlocking {
        val json = """[{"name": "Video Games"}, {"name": "Travel"}]"""
        stubStream(json.toByteArray())
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `unrecognised JSON shape returns WrongFormat`() = runBlocking {
        val json = """{"completely_unrelated": "structure"}"""
        stubStream(json.toByteArray())

        val result = importer.import(fakeUri)

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `malformed JSON returns WrongFormat (not crash)`() = runBlocking {
        stubStream("not JSON at all".toByteArray())

        val result = importer.import(fakeUri)

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `null InputStream returns IoError`() = runBlocking {
        every { contentResolver.openInputStream(fakeUri) } returns null

        val result = importer.import(fakeUri)

        assertTrue("expected IoError, got $result", result is ImportResult.IoError)
    }

    @Test
    fun `DYI ZIP with newer schema at the standard path is parsed`() = runBlocking {
        val zipBytes = buildZip(
            mapOf(
                // Standard 2024 DYI layout under your_facebook_activity/ads_information/.
                // Include some non-target entries so we exercise the streaming skip path.
                "facebook-handle-2026/index.html" to "<html></html>",
                "facebook-handle-2026/your_facebook_activity/ads_information/other_categories_used_to_reach_you.json" to
                    """{"label_values":[{"label":"x","vec":[{"value":"Video Games"},{"value":"Travel"}]}]}""",
                "facebook-handle-2026/messages/inbox/conversation.json" to "[]"
            )
        )
        stubStream(zipBytes)
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `DYI ZIP with only the older ads_interests path is parsed`() = runBlocking {
        val zipBytes = buildZip(
            mapOf(
                "facebook-handle/ads_information/ads_interests.json" to
                    """{"topics_v2": ["Cooking"]}"""
            )
        )
        stubStream(zipBytes)
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(1, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `DYI ZIP prefers the newer schema when both files are present`() = runBlocking {
        // Some exports include both — newer path takes precedence because the older one
        // can be empty or schema-truncated on accounts that opted into the newer privacy
        // surfaces. Lock that priority in so we don't accidentally read the empty file.
        val zipBytes = buildZip(
            mapOf(
                "facebook-handle/ads_information/ads_interests.json" to
                    """{"topics_v2": []}""",
                "facebook-handle/your_facebook_activity/ads_information/other_categories_used_to_reach_you.json" to
                    """{"label_values":[{"label":"x","vec":[{"value":"Video Games"},{"value":"Travel"}]}]}"""
            )
        )
        stubStream(zipBytes)
        coEvery { dao.upsert(any()) } returns Unit

        val result = importer.import(fakeUri)

        assertEquals(2, (result as ImportResult.Success).categoryCount)
    }

    @Test
    fun `DYI ZIP with no recognised entries returns WrongFormat`() = runBlocking {
        val zipBytes = buildZip(
            mapOf(
                "facebook-handle/profile/profile_information.json" to "{}",
                "facebook-handle/messages/inbox/x.json" to "[]"
            )
        )
        stubStream(zipBytes)

        val result = importer.import(fakeUri)

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    private fun stubStream(bytes: ByteArray) {
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
