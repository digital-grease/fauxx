package com.fauxx.safety

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.Normalizer

/**
 * Test-only re-implementation of the production harmful-query matcher, kept in ONE place so
 * the corpus-audit tests cannot drift apart from each other or from production. Mirrors
 * [com.fauxx.data.querybank.QueryBlocklist.isBlocked] exactly:
 *  - NFKC normalize + strip zero-width/soft-hyphen characters + lowercase + trim (both the
 *    query and the stored terms), so fullwidth/zero-width evasion is caught identically.
 *  - case-insensitive substring match for phrase terms (class_a + self_signal).
 *  - IGNORE_CASE containsMatchIn for regex patterns; malformed patterns dropped silently.
 *
 * The real [com.fauxx.data.querybank.QueryBlocklist] needs an Android Context + a
 * LocaleManager, neither of which a locale-switching JVM audit can spin up cleanly — hence a
 * faithful re-implementation. If QueryBlocklist's matching changes, update this in lockstep
 * (the audits and production matcher are intentionally a single source of truth here).
 */
object CorpusSafetyMatchers {
    private val gson = Gson()

    /** Mirrors QueryBlocklist.ZERO_WIDTH_CHARS. */
    private val ZERO_WIDTH = Regex("[\u200B-\u200D\u2060\uFEFF\u00AD]")

    private data class HarmfulShape(
        @SerializedName("class_a_terms") val classATerms: List<String> = emptyList(),
        @SerializedName("self_signal_terms") val selfSignalTerms: List<String> = emptyList(),
        @SerializedName("regex_patterns") val regexPatterns: List<String> = emptyList(),
    )

    /** Mirrors QueryBlocklist.normalizeForMatch. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(ZERO_WIDTH, "")
            .lowercase()
            .trim()

    /** Build a `(query) -> Boolean` harmful matcher from a `harmful_queries` JSON string. */
    fun harmfulBlocker(harmfulJson: String): (String) -> Boolean {
        val parsed = gson.fromJson(harmfulJson, HarmfulShape::class.java)
        val terms = (parsed.classATerms + parsed.selfSignalTerms)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val regexes = parsed.regexPatterns.mapNotNull {
            // (?U) mirrors QueryBlocklist: makes \b/\w Unicode-aware so Cyrillic word-boundary
            // patterns fire (ASCII-only \b never matches before a non-ASCII letter).
            runCatching { Regex("(?U)$it", RegexOption.IGNORE_CASE) }.getOrNull()
        }
        return { query ->
            val n = normalize(query)
            terms.any { n.contains(it) } || regexes.any { it.containsMatchIn(n) }
        }
    }

    private data class BlocklistShape(
        val domains: List<String> = emptyList(),
        val patterns: List<String> = emptyList(),
    )

    /**
     * Build a `(host) -> Boolean` matcher from a `blocklist.json` string. Mirrors
     * [com.fauxx.data.crawllist.DomainBlocklist.isBlocked]: lowercase + trimStart('.'),
     * exact-domain match, subdomain (`endsWith(".$domain")`), and IGNORE_CASE regex
     * patterns. No `(?U)` here — blocklist domains/patterns are ASCII hosts/IP ranges.
     */
    fun domainBlocker(blocklistJson: String): (String) -> Boolean {
        val parsed = gson.fromJson(blocklistJson, BlocklistShape::class.java)
        val domains = parsed.domains.map { it.lowercase().trim() }.toSet()
        val patterns = parsed.patterns.mapNotNull {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        return { host ->
            val n = host.lowercase().trimStart('.')
            n in domains ||
                domains.any { n.endsWith(".$it") } ||
                patterns.any { it.containsMatchIn(n) }
        }
    }
}
