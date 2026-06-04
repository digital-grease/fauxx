package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.safety.CorpusSafetyMatchers
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * CI regression guard: every bundled crawl URL (the corpus AdPollution / Cookie /
 * DiverseBrowsing modules load in a background WebView) is checked at build time against
 * [com.fauxx.data.crawllist.DomainBlocklist] for its locale-agnostic blocklist
 * (`blocklist.json`). A blocklisted host in the crawl corpus would otherwise only (maybe)
 * be caught at runtime, and a sensitive-resource host (e.g. a domestic-violence site) loaded
 * as decoy traffic is itself a self-signal harm — the exact thing the blocklist prevents.
 *
 * Also asserts each entry is structurally sound: a parseable host, a non-blank URL, and a
 * category that maps to a [CategoryPool] value (production drops unmatched categories at
 * load, silently shrinking the corpus).
 *
 * Host extraction uses [java.net.URI] (a plain JVM parser) rather than `android.net.Uri`,
 * which would require Robolectric. The domain matcher is
 * [CorpusSafetyMatchers.domainBlocker], a faithful re-implementation of
 * `DomainBlocklist.isBlocked`. Runs as a standard JVM unit test reading `src/main/assets`.
 */
class CrawlUrlsCorpusAuditTest {

    private val assetsRoot = File("src/main/assets")
    private val gson = Gson()

    private data class CrawlEntryShape(
        val url: String = "",
        val category: String = "",
    )

    private val listType = object : TypeToken<List<CrawlEntryShape>>() {}.type
    private val validCategories = CategoryPool.values().map { it.name }.toSet()

    /** A shipped crawl corpus + the blocklist it must not violate. */
    private data class CrawlTarget(val tag: String, val crawlPath: String)

    // blocklist.json is locale-agnostic (hosts/IP ranges), so every locale audits against it.
    private val targets = listOf(
        CrawlTarget("en", "crawl_urls.json"),
        CrawlTarget("es", "crawl_urls/es.json"),
        CrawlTarget("fr", "crawl_urls/fr.json"),
        CrawlTarget("ru", "crawl_urls/ru.json"),
    )

    @Test
    fun `no bundled crawl URL targets a blocklisted host or is malformed`() {
        val blocklistFile = File(assetsRoot, "blocklist.json")
        assertTrue(
            "blocklist.json missing at ${blocklistFile.absolutePath} (cwd=${File(".").absolutePath}); " +
                "run from the app module root.",
            blocklistFile.exists()
        )
        val blocked = CorpusSafetyMatchers.domainBlocker(blocklistFile.readText())

        val violations = mutableListOf<String>()
        var audited = 0

        for (t in targets) {
            val crawlFile = File(assetsRoot, t.crawlPath)
            // A locale that hasn't shipped its crawl corpus yet is not a failure here.
            if (!crawlFile.exists()) continue
            audited++

            val entries: List<CrawlEntryShape> = gson.fromJson(crawlFile.readText(), listType)
            assertTrue("[${t.tag}] crawl corpus is empty: ${t.crawlPath}", entries.isNotEmpty())

            entries.forEachIndexed { i, e ->
                if (e.url.isBlank()) {
                    violations += "[${t.tag}][$i] blank url"
                    return@forEachIndexed
                }
                val host = runCatching { URI(e.url).host }.getOrNull()
                if (host.isNullOrBlank()) {
                    violations += "[${t.tag}][$i] unparseable host: \"${e.url}\""
                    return@forEachIndexed
                }
                if (blocked(host)) {
                    violations += "[${t.tag}][$i] BLOCKLISTED host \"$host\": ${e.url}"
                }
                if (e.category.uppercase() !in validCategories) {
                    violations += "[${t.tag}][$i] unknown category \"${e.category}\": ${e.url}"
                }
            }
        }

        assertTrue("No crawl corpora audited (cwd=${File(".").absolutePath})", audited > 0)
        assertEquals(
            buildString {
                append("Bundled crawl URLs failed the safety/integrity audit. Remove any ")
                append("blocklisted host from the crawl corpus; fix any unparseable URL or ")
                append("unknown category. See blocklist.json for the blocked hosts/patterns.\n")
                violations.forEach { appendLine("  $it") }
            },
            0,
            violations.size
        )
    }
}
