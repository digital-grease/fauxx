package com.fauxx

import com.fauxx.safety.CorpusSafetyMatchers
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * CI regression guard: parses every JSON file under each locale's `query_banks`
 * directory against that locale's harmful-queries blocklist, and fails the build
 * if any corpus entry matches a harmful trigger.
 *
 * This is the enforcement point for the "Self-Signal Harm" safety requirement in
 * CLAUDE.md and the multilingual rollout's safety gate (see
 * `.devloop/spikes/multilingual-support.md`). Without this test, a contributor —
 * or the LLM that drafts the bank translations — could ship a query like "988
 * mental health" or "024 línea suicidio" or "3114 prévention suicide" embedded
 * in an otherwise innocent corpus. Runtime filtering by [QueryBlocklist] would
 * still catch it, but corpus drift means silent runtime drops and degraded query
 * variety. We catch it here, at build time.
 *
 * Locale coverage:
 *   - en: legacy `harmful_queries.json` audits the legacy `query_banks/<cat>.json`
 *   - es: `harmful_queries/es.json` audits `query_banks/es/<cat>.json`
 *   - fr: `harmful_queries/fr.json` audits `query_banks/fr/<cat>.json`
 *   - ru: `harmful_queries/ru.json` audits `query_banks/ru/<cat>.json`
 *
 * The blocklist matcher is [com.fauxx.safety.CorpusSafetyMatchers.harmfulBlocker],
 * a faithful re-implementation of [com.fauxx.data.querybank.QueryBlocklist.isBlocked]
 * (that class needs an Android Context + [com.fauxx.locale.LocaleManager], which a
 * locale-switching JVM test cannot spin up cleanly). Shared with MarkovQuerySanityTest
 * so the two audits cannot drift from each other or from production.
 *
 * Runs as a standard JVM unit test (not instrumentation). Asset files are read
 * directly from the module's `src/main/assets/` path rather than through Android's
 * AssetManager.
 */
class QueryBankCorpusAuditTest {

    private val assetsRoot = File("src/main/assets")
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    private data class HarmfulShape(
        @SerializedName("class_a_terms") val classATerms: List<String> = emptyList(),
        @SerializedName("self_signal_terms") val selfSignalTerms: List<String> = emptyList(),
        @SerializedName("regex_patterns") val regexPatterns: List<String> = emptyList()
    )

    /** Locale audit target: harmful_queries file path + query_banks dir path. */
    private data class AuditTarget(
        val tag: String,
        val harmfulPath: String,
        val banksDir: String,
    )

    private val targets = listOf(
        AuditTarget("en", "harmful_queries.json", "query_banks"),
        AuditTarget("es", "harmful_queries/es.json", "query_banks/es"),
        AuditTarget("fr", "harmful_queries/fr.json", "query_banks/fr"),
        AuditTarget("ru", "harmful_queries/ru.json", "query_banks/ru"),
    )

    @Test
    fun `every query in every locale bank passes its locale's blocklist`() {
        val allViolations = mutableListOf<String>()
        var localesAudited = 0

        for (target in targets) {
            val harmfulFile = File(assetsRoot, target.harmfulPath)
            val banksDir = File(assetsRoot, target.banksDir)

            // A locale that hasn't shipped its files yet is not a failure here —
            // HarmfulQueriesLocaleAuditTest is what enforces the safety blocklist
            // existence per locale. This test is about corpus drift.
            if (!harmfulFile.exists() || !banksDir.isDirectory) continue
            localesAudited++

            val blocker = CorpusSafetyMatchers.harmfulBlocker(harmfulFile.readText())
            banksDir.listFiles { f -> f.extension == "json" }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val queries: List<String> = gson.fromJson(file.readText(), stringListType)
                    queries.forEachIndexed { idx, q ->
                        if (blocker(q)) {
                            allViolations += "[${target.tag}] ${file.name}[$idx]: \"$q\""
                        }
                    }
                }
        }

        assert(localesAudited > 0) {
            "No locales audited. Expected at least the EN bank at " +
                "src/main/assets/harmful_queries.json (cwd=${File(".").absolutePath}). " +
                "Run from the app module root."
        }

        assertEquals(
            buildString {
                append("Query bank entries match the harmful-query guard for their ")
                append("own locale. Remove them from the JSON or re-word to avoid the ")
                append("trigger. If the trigger itself is wrong, adjust the locale's ")
                append("harmful_queries blocklist. See .devloop/spikes/multilingual-support.md.\n")
                allViolations.forEach { appendLine("  $it") }
            },
            0,
            allViolations.size
        )
    }

    @Test
    fun `every shipped harmful_queries file has non-empty lists`() {
        for (target in targets) {
            val file = File(assetsRoot, target.harmfulPath)
            if (!file.exists()) continue
            val parsed = gson.fromJson(file.readText(), HarmfulShape::class.java)
            assert(parsed.classATerms.isNotEmpty()) {
                "[${target.tag}] class_a_terms must not be empty in ${target.harmfulPath}"
            }
            assert(parsed.selfSignalTerms.isNotEmpty()) {
                "[${target.tag}] self_signal_terms must not be empty in ${target.harmfulPath}"
            }
            assert(parsed.regexPatterns.isNotEmpty()) {
                "[${target.tag}] regex_patterns must not be empty in ${target.harmfulPath}"
            }
        }
    }

}
