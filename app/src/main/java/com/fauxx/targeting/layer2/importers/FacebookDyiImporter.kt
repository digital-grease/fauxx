package com.fauxx.targeting.layer2.importers

import android.content.Context
import android.net.Uri
import com.fauxx.data.querybank.CategoryPool
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
 * Reads ad-interest categories from a Facebook DYI (Download Your Information) archive
 * and persists them to [PlatformProfileCache] under platform key "facebook".
 *
 * Supported export shapes (probed in priority order across recognised file paths):
 *
 * 1. `your_facebook_activity/ads_information/other_categories_used_to_reach_you.json`
 *    (2024 schema). Object with a `label_values` array whose entries contain a `vec`
 *    of `{"value": "<topic>"}` objects:
 *    ```
 *    {"label_values": [{"label": "...", "vec": [{"value": "Travel"}, ...]}]}
 *    ```
 *
 * 2. `ads_information/ads_interests.json` (older schema). Several shape variants:
 *    - `{"topics_v2": ["Topic", ...]}`
 *    - `{"ads_interests": ["Topic", ...]}`
 *    - Root JsonArray of `{"name": "Topic"}` or `{"value": "Topic"}`
 *
 * 3. Any matching JSON inside the ZIP whose entry name ends with `ads_interests.json` or
 *    `other_categories_used_to_reach_you.json`, regardless of enclosing directory — DYI
 *    exports sometimes wrap everything under an additional locale-named root directory.
 *
 * Parsing is lenient (JsonElement walk) so a Facebook schema tweak doesn't break the
 * importer outright. WrongFormat is returned with the searched-for filename so the user
 * can re-export the correct DYI subset.
 */
@Singleton
class FacebookDyiImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryMapper: CategoryMapper,
    private val platformProfileDao: PlatformProfileDao,
    private val profileSnapshotDao: com.fauxx.targeting.layer2.ProfileSnapshotDao,
    private val clock: Clock
) : AdProfileImporter {

    override val source = ImportSource.FACEBOOK_DYI

    private val gson = Gson()

    override suspend fun import(
        uri: Uri,
        series: com.fauxx.targeting.layer2.SnapshotSeries,
    ): ImportResult = withContext(Dispatchers.IO) {
        val rawStrings = try {
            extractRawCategories(uri)
        } catch (e: java.io.IOException) {
            Timber.w(e, "Failed to open DYI archive at $uri")
            return@withContext ImportResult.IoError(source, e)
        } catch (e: SecurityException) {
            Timber.w(e, "SAF permission denied for DYI archive at $uri")
            return@withContext ImportResult.IoError(source, e)
        } catch (e: JsonSyntaxException) {
            Timber.w(e, "Malformed JSON inside DYI archive at $uri")
            return@withContext ImportResult.ParseError(
                source,
                "The export's JSON file was malformed.",
                e
            )
        }

        if (rawStrings == null) {
            return@withContext ImportResult.WrongFormat(
                source,
                com.fauxx.R.string.targeting_import_wrong_format_facebook
            )
        }
        if (rawStrings.isEmpty()) {
            persistCategories(emptySet(), series)
            return@withContext ImportResult.Success(source, 0)
        }

        val mapped = categoryMapper.mapAll(rawStrings)
        persistCategories(mapped, series)
        ImportResult.Success(source, mapped.size)
    }

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
            // Priority: newer "other_categories_used_to_reach_you.json" wins over the older
            // "ads_interests.json" if both exist (the 2024 schema is more reliable).
            var newerJson: String? = null
            var olderJson: String? = null
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                when {
                    name.endsWith("other_categories_used_to_reach_you.json", ignoreCase = true) ->
                        newerJson = zip.readBytes().toString(Charsets.UTF_8)
                    name.endsWith("ads_interests.json", ignoreCase = true) &&
                        olderJson == null ->
                        olderJson = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            return when {
                newerJson != null -> parseLabelValuesShape(newerJson)
                    ?: parseAdsInterestsShape(newerJson)
                olderJson != null -> parseAdsInterestsShape(olderJson)
                    ?: parseLabelValuesShape(olderJson)
                else -> null
            }
        }
    }

    private fun extractFromJson(input: InputStream): List<String>? {
        val text = input.bufferedReader().readText()
        // Try both shapes — user might have unwrapped either file individually.
        return parseLabelValuesShape(text) ?: parseAdsInterestsShape(text)
    }

    /**
     * Parse the 2024 `label_values` shape. Walks every `label_values[].vec[]` entry and
     * harvests `value` strings — most DYI category files follow this structure.
     */
    private fun parseLabelValuesShape(json: String): List<String>? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val labelValues = root.asJsonObject["label_values"]
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return null
        val collected = mutableSetOf<String>()
        for (group in labelValues) {
            if (!group.isJsonObject) continue
            val vec = group.asJsonObject["vec"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?: continue
            for (entry in vec) {
                entry.asTopicStringOrNull()?.let { collected.add(it) }
            }
        }
        return collected.toList().takeIf { it.isNotEmpty() || labelValues.size() > 0 }
    }

    /**
     * Parse the older `ads_interests.json` shape variants:
     *  - `{"topics_v2": ["X", ...]}`
     *  - `{"ads_interests": ["X", ...]}`
     *  - Root JsonArray of `{"name"/"value": "X"}` or plain strings.
     */
    private fun parseAdsInterestsShape(json: String): List<String>? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null

        if (root.isJsonArray) {
            val collected = root.asJsonArray.mapNotNull { it.asTopicStringOrNull() }
            return collected.takeIf { it.isNotEmpty() } ?: return null
        }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject

        // Try a handful of likely keys — Facebook has shipped exports under at least
        // these names across versions.
        for (key in listOf("topics_v2", "ads_interests", "interests")) {
            val arr = obj[key]?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val collected = arr.mapNotNull { el ->
                if (el.isJsonPrimitive) el.asString.takeIf { it.isNotBlank() }
                else el.asTopicStringOrNull()
            }
            return collected
        }
        return null
    }

    private fun JsonElement.asTopicStringOrNull(): String? {
        if (isJsonPrimitive) return asString.takeIf { it.isNotBlank() }?.trim()
        if (!isJsonObject) return null
        val obj = asJsonObject
        val value = obj["value"]?.takeIf { it.isJsonPrimitive }?.asString
        if (!value.isNullOrBlank()) return value.trim()
        val name = obj["name"]?.takeIf { it.isJsonPrimitive }?.asString
        if (!name.isNullOrBlank()) return name.trim()
        return null
    }

    private suspend fun persistCategories(
        mapped: Set<CategoryPool>,
        series: com.fauxx.targeting.layer2.SnapshotSeries,
    ) {
        val json = gson.toJson(mapped.map { it.name })
        val now = clock.currentTimeMillis()
        // The control series is measurement-only (#172): never touch the cache Layer 2 reads, so
        // a control import can't influence targeting. Only the poisoned import updates the cache.
        if (series == com.fauxx.targeting.layer2.SnapshotSeries.POISONED) {
            platformProfileDao.upsert(
                PlatformProfileCache(
                    platformName = source.platformId,
                    scrapedCategoriesJson = json,
                    lastScraped = now
                )
            )
        }
        // Append an immutable IMPORT snapshot so Layer 2 can compute drift over time (#170 E1),
        // tagged with its experimental arm (#172 E3).
        profileSnapshotDao.insert(
            com.fauxx.targeting.layer2.ProfileSnapshot(
                platformName = source.platformId,
                source = com.fauxx.targeting.layer2.SnapshotSource.IMPORT,
                scrapedCategoriesJson = json,
                capturedAt = now,
                series = series,
            )
        )
    }
}
