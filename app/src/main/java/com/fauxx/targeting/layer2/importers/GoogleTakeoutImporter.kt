package com.fauxx.targeting.layer2.importers

import android.content.Context
import android.net.Uri
import com.fauxx.targeting.layer2.CategoryMapper
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.util.Clock
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads an interest-category export from a Google Takeout archive (ZIP or raw JSON) and
 * persists the mapped categories to [PlatformProfileCache] under platform key "google".
 *
 * Supported export shapes (probed in priority order):
 *
 * 1. **My Ad Center JSON** (2024+ format): an object with an `interests` array of
 *    `{"category": "<topic>"}` objects, or in some variants `{"name": "<topic>"}`.
 *    File path inside the Takeout ZIP: `Takeout/My Ad Center/MyAdCenter.json`.
 *
 * 2. **My Ad Center "advertisingTopics" variant**: `{"advertisingTopics": ["topic1", ...]}`.
 *    Same file location.
 *
 * 3. **My Activity Ads fallback**: `Takeout/My Activity/Ads/MyActivity.json` — an array
 *    of activity records, some of which have a `details` array containing
 *    `{"name": "Ad Targeting", ... "title": "<topic>"}` entries. Older / partial exports.
 *
 * Schema is parsed leniently with `JsonElement` rather than typed POJOs so a Google
 * schema tweak doesn't break the importer outright. If none of the shapes match, the
 * importer returns [ImportResult.WrongFormat] with a specific message naming the file
 * path it looked for — guides the user back to the right Takeout subset selection.
 */
@Singleton
class GoogleTakeoutImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryMapper: CategoryMapper,
    private val platformProfileDao: PlatformProfileDao,
    private val profileSnapshotDao: com.fauxx.targeting.layer2.ProfileSnapshotDao,
    private val clock: Clock
) : AdProfileImporter {

    override val source = ImportSource.GOOGLE_TAKEOUT

    private val gson = Gson()

    override suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val rawStrings = try {
            extractRawCategories(uri)
        } catch (e: java.io.IOException) {
            Timber.w(e, "Failed to open Takeout archive at $uri")
            return@withContext ImportResult.IoError(source, e)
        } catch (e: SecurityException) {
            Timber.w(e, "SAF permission denied for Takeout archive at $uri")
            return@withContext ImportResult.IoError(source, e)
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Malformed JSON inside Takeout archive at $uri")
            return@withContext ImportResult.ParseError(
                source,
                "The export's JSON file was malformed.",
                e
            )
        }

        if (rawStrings == null) {
            return@withContext ImportResult.WrongFormat(
                source,
                com.fauxx.R.string.targeting_import_wrong_format_google
            )
        }
        if (rawStrings.isEmpty()) {
            // Found the file but it has no categories. Treat as a Success with 0 so the
            // user gets explicit feedback that the import worked but their ad profile
            // is empty (signed-in but Google has no targeting topics for them).
            persistCategories(emptySet())
            return@withContext ImportResult.Success(source, 0)
        }

        val mapped = categoryMapper.mapAll(rawStrings)
        persistCategories(mapped)
        ImportResult.Success(source, mapped.size)
    }

    /**
     * Open [uri] and walk it (ZIP entries or raw JSON top-level) until a recognised
     * shape is found. Returns the raw platform topic strings, or null if the archive
     * doesn't contain a recognised shape.
     */
    private fun extractRawCategories(uri: Uri): List<String>? {
        val resolver = context.contentResolver
        val rawStream = resolver.openInputStream(uri)
            ?: throw java.io.IOException("openInputStream returned null for $uri")
        return BufferedInputStream(rawStream).use { buffered ->
            buffered.mark(4)
            val magic = ByteArray(2)
            val read = buffered.read(magic, 0, 2)
            buffered.reset()
            val isZip = read == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
            if (isZip) extractFromZip(buffered) else extractFromJson(buffered)
        }
    }

    private fun extractFromZip(buffered: BufferedInputStream): List<String>? {
        ZipInputStream(buffered).use { zip ->
            // First pass priority: collect MyAdCenter.json bytes if present.
            // Second priority: MyActivity.json fallback. Stream-read so we don't
            // buffer multi-gigabyte non-target entries.
            var myAdCenterJson: String? = null
            var myActivityJson: String? = null
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                when {
                    name.endsWith("MyAdCenter.json", ignoreCase = true) -> {
                        myAdCenterJson = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    name.endsWith("Ads/MyActivity.json", ignoreCase = true) &&
                        myActivityJson == null -> {
                        myActivityJson = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
                // Don't break early on MyAdCenter — there may be multiple matches
                // (e.g., split-volume Takeouts); use the last one seen.
            }
            return when {
                myAdCenterJson != null -> parseMyAdCenter(myAdCenterJson)
                myActivityJson != null -> parseMyActivityAds(myActivityJson)
                else -> null
            }
        }
    }

    /**
     * Treat the input as a raw JSON file (user picked the JSON directly via SAF rather
     * than the enclosing ZIP). Tries MyAdCenter shape first, then MyActivity shape.
     */
    private fun extractFromJson(input: InputStream): List<String>? {
        val text = input.bufferedReader().readText()
        return parseMyAdCenter(text) ?: parseMyActivityAds(text)
    }

    /**
     * Parse a MyAdCenter.json document. Tolerates several shape variants:
     *  - `{"interests": [{"category": "X"}, ...]}` — current shape
     *  - `{"interests": [{"name": "X"}, ...]}` — variant seen in some exports
     *  - `{"advertisingTopics": ["X", "Y"]}` — flat-string variant
     *  - Root-level JsonArray of `{"category": "X"}` — pre-wrapper-object exports
     *
     * Returns null if the JSON parses but matches none of the expected shapes (so the
     * caller can fall back to the MyActivity path).
     */
    private fun parseMyAdCenter(json: String): List<String>? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonObject && !root.isJsonArray) return null

        // Shape: root array of {"category"/"name": "X"}
        if (root.isJsonArray) {
            val collected = root.asJsonArray.mapNotNull { it.asTopicStringOrNull() }
            return collected.takeIf { it.isNotEmpty() } ?: return null
        }

        val obj = root.asJsonObject
        val interestsArray = obj["interests"]?.takeIf { it.isJsonArray }?.asJsonArray
        if (interestsArray != null) {
            val collected = interestsArray.mapNotNull { it.asTopicStringOrNull() }
            return collected
        }
        val topicsArray = obj["advertisingTopics"]?.takeIf { it.isJsonArray }?.asJsonArray
        if (topicsArray != null) {
            val collected = topicsArray.mapNotNull { el ->
                if (el.isJsonPrimitive) el.asString else el.asTopicStringOrNull()
            }
            return collected
        }
        return null
    }

    /**
     * Parse a MyActivity.json document, looking for activity records that describe ad
     * targeting. The shape is roughly:
     * ```
     * [
     *   {"header": "Ads", "title": "Saw an ad for ...",
     *    "details": [{"name": "Ad Targeting", "title": "<topic>"}, ...]},
     *   ...
     * ]
     * ```
     * We collect every `details[].title` whose sibling `name == "Ad Targeting"`.
     */
    private fun parseMyActivityAds(json: String): List<String>? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonArray) return null
        val collected = mutableSetOf<String>()
        for (entry in root.asJsonArray) {
            if (!entry.isJsonObject) continue
            val details = entry.asJsonObject["details"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?: continue
            for (d in details) {
                if (!d.isJsonObject) continue
                val name = d.asJsonObject["name"]?.takeIf { it.isJsonPrimitive }?.asString
                val title = d.asJsonObject["title"]?.takeIf { it.isJsonPrimitive }?.asString
                if (name.equals("Ad Targeting", ignoreCase = true) && !title.isNullOrBlank()) {
                    collected.add(title.trim())
                }
            }
        }
        return collected.toList().takeIf { it.isNotEmpty() } ?: return null
    }

    private fun JsonElement.asTopicStringOrNull(): String? {
        if (!isJsonObject) {
            return if (isJsonPrimitive) asString.takeIf { it.isNotBlank() } else null
        }
        val obj = asJsonObject
        val category = obj["category"]?.takeIf { it.isJsonPrimitive }?.asString
        if (!category.isNullOrBlank()) return category.trim()
        val name = obj["name"]?.takeIf { it.isJsonPrimitive }?.asString
        if (!name.isNullOrBlank()) return name.trim()
        return null
    }

    private suspend fun persistCategories(mapped: Set<com.fauxx.data.querybank.CategoryPool>) {
        val json = gson.toJson(mapped.map { it.name })
        val now = clock.currentTimeMillis()
        platformProfileDao.upsert(
            PlatformProfileCache(
                platformName = source.platformId,
                scrapedCategoriesJson = json,
                lastScraped = now
            )
        )
        // Append an immutable IMPORT snapshot so Layer 2 can compute drift over time (#170 E1).
        profileSnapshotDao.insert(
            com.fauxx.targeting.layer2.ProfileSnapshot(
                platformName = source.platformId,
                source = com.fauxx.targeting.layer2.SnapshotSource.IMPORT,
                scrapedCategoriesJson = json,
                capturedAt = now,
            )
        )
    }
}
