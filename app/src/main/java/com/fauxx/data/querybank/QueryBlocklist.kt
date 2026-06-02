package com.fauxx.data.querybank

import android.content.Context
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blocklist for query CONTENT (distinct from [com.fauxx.data.crawllist.DomainBlocklist]
 * which blocks URL destinations). The blocklist defends against two specific risks
 * Fauxx's synthetic search traffic could otherwise create for the user:
 *
 *  - **Class A — law-enforcement / criminal-attention risk**: queries whose appearance
 *    in a user's broker / ISP / search-engine profile could plausibly trigger a
 *    criminal investigation, watchlist placement, or platform ban. Includes CSAM,
 *    bomb / explosive synthesis, weapon conversion (auto-fire, ghost guns), drug
 *    synthesis (fentanyl, meth, sarin, ricin, etc.), terrorism recruitment, hire-a-
 *    hitman, trafficking, doxing / stalkerware, identity-theft (stolen SSN / cards /
 *    passports), and cybercrime tooling (ransomware kits, carding, hospital / grid
 *    intrusion). Safe-to-execute-by-humans is NOT a defense — the harm is the
 *    profile entry, not the result.
 *  - **Class B — self-signal harm**: queries that are individually benign (often
 *    lifesaving for a real searcher) but create a false first-person distress signal
 *    when dispatched as synthetic user activity. Example: a 988 query from a real
 *    user is a lifeline; the same query injected as noise creates a false "user in
 *    crisis" flag in broker / government / insurer profiles with real-world
 *    consequences (wellness checks, insurance denial, watchlists). Also covers
 *    asylum / immigration, bankruptcy, abortion, eviction — vulnerability signals
 *    that should never appear as synthetic noise.
 *
 * **Out of scope** (intentionally, do NOT add): medical misinformation / quackery
 * (anti-vaccine, bleach / MMS cures, colloidal silver, apricot seeds, ivermectin),
 * conspiracy theories, fringe-science searches. Searching them won't put the user
 * on a watchlist or look like a crisis. They are a real concern for algorithmic-
 * feed pollution (the user's *real* recommendations getting pulled toward quackery
 * via their inferred profile), but the right defense there is corpus selection
 * (don't ship those topics in `query_banks/`), not runtime blocking.
 *
 * Enforced at four chokepoints to provide defense in depth:
 *  1. [QueryBankManager.getQueries] — pre-filters the corpus at load time
 *  2. [MarkovQueryGenerator.generate] — post-generation check with resample fallback
 *  3. [MarkovQueryGenerator.injectSeedPhrases] — rejects user-supplied harmful seeds
 *  4. [com.fauxx.engine.modules.SearchPoisonModule.onAction] — final dispatch gate;
 *     skips the action cycle rather than dispatching
 *
 * **Locale-aware**: the active locale (via [LocaleManager]) selects which
 * `harmful_queries/<localeTag>.json` to load. English (the legacy ship language) also
 * accepts a fallback to the legacy single-file `harmful_queries.json` path so existing
 * tests and old asset layouts continue to work. Non-English locales have no legacy
 * fallback — if their locale-specific file is missing, [loadFailed] is set and every
 * query is blocked. This is by design: an English blocklist would not catch the
 * Spanish/French equivalents of crisis-line numbers, DV hotlines, or self-signal
 * phrases, and silently substituting it would degrade the safety guarantee. See
 * `.devloop/spikes/multilingual-support.md` and the user-memory file
 * `project_multilingual_safety_gate.md` for the rationale.
 *
 * **Fail-closed**: if the active locale's harmful-queries file fails to load,
 * [loadFailed] returns `true` for that locale and [isBlocked] returns `true` for
 * every input until either a different locale is selected (whose file does load)
 * or the asset is fixed and the process restarts.
 */
@Singleton
class QueryBlocklist @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localeManager: LocaleManager
) {
    private data class BlocklistData(
        val phraseTerms: Set<String>,
        val regexes: List<Regex>,
        val loadFailed: Boolean
    )

    private val perLocale = ConcurrentHashMap<SupportedLocale, BlocklistData>()

    init {
        // Eagerly resolve for the active locale so [loadFailed] is set at injection time
        // before any module tries to filter a query.
        dataForCurrentLocale()
    }

    /**
     * `true` if the active locale's harmful-queries file could not be loaded. When
     * this is set, [isBlocked] returns `true` for every query in this locale.
     * Callers may expose this as a health warning. Recomputed on each access; switching
     * locales to one that loads cleanly will flip this back to false.
     */
    val loadFailed: Boolean
        get() = dataForCurrentLocale().loadFailed

    /**
     * Returns `true` if [query] matches any harmful phrase or regex pattern in the
     * active locale's blocklist.
     *
     * Comparison is case-insensitive and uses substring match for phrase terms (multi-word
     * anchors prevent false positives on common single words — see contributor rules in
     * each `harmful_queries/<localeTag>.json`).
     *
     * If [loadFailed] is set for the active locale, returns `true` for every input.
     */
    fun isBlocked(query: String): Boolean {
        val data = dataForCurrentLocale()
        if (data.loadFailed) return true
        val normalized = normalizeForMatch(query)
        if (data.phraseTerms.any { normalized.contains(it) }) return true
        if (data.regexes.any { it.containsMatchIn(normalized) }) return true
        return false
    }

    /**
     * Canonicalize text before matching so common evasion tricks cannot smuggle a harmful
     * phrase past the substring/regex guard:
     *  - NFKC folds compatibility forms (fullwidth letters/digits, ligatures) to their
     *    canonical equivalents, so "ｂｏｍｂ" / "９８８" match "bomb" / "988".
     *  - Zero-width and soft-hyphen characters (often injected mid-word) are stripped.
     *  - Lowercased and trimmed for case-insensitive substring matching.
     *
     * Applied to BOTH the incoming query and the stored blocklist terms so the two sides
     * canonicalize identically. Cross-script homoglyph confusables (e.g. Cyrillic "а" for
     * Latin "a") are NOT folded here — that needs a full Unicode confusables table and is
     * tracked as a follow-up.
     */
    private fun normalizeForMatch(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(ZERO_WIDTH_CHARS, "")
            .lowercase()
            .trim()

    private fun dataForCurrentLocale(): BlocklistData {
        val locale = localeManager.currentLocale
        return perLocale.computeIfAbsent(locale) { loadFor(it) }
    }

    private fun loadFor(locale: SupportedLocale): BlocklistData {
        val localePath = "harmful_queries/${locale.tag}.json"
        val parsed = tryLoadFile(localePath)
            ?: if (locale == SupportedLocale.EN) tryLoadFile(LEGACY_PATH) else null

        if (parsed == null) {
            Timber.e(
                "Failed to load harmful_queries for locale=%s — failing closed, all " +
                    "queries will be blocked while this locale is active",
                locale.tag
            )
            return BlocklistData(emptySet(), emptyList(), loadFailed = true)
        }

        if (parsed.classATerms.isEmpty() &&
            parsed.selfSignalTerms.isEmpty() &&
            parsed.regexPatterns.isEmpty()
        ) {
            Timber.e(
                "harmful_queries for locale=%s loaded but all lists empty — failing closed",
                locale.tag
            )
            return BlocklistData(emptySet(), emptyList(), loadFailed = true)
        }

        val phraseTerms = (parsed.classATerms + parsed.selfSignalTerms)
            .map { normalizeForMatch(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val regexes = parsed.regexPatterns.mapNotNull {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        return BlocklistData(phraseTerms, regexes, loadFailed = false)
    }

    private fun tryLoadFile(path: String): HarmfulQueriesJson? = try {
        val json = context.assets.open(path).bufferedReader().readText()
        val type = object : TypeToken<HarmfulQueriesJson>() {}.type
        Gson().fromJson<HarmfulQueriesJson>(json, type)
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Pre-multilingual asset layout. Treated as the English blocklist when no
         * `harmful_queries/en.json` is present. Kept for backwards compatibility with
         * existing tests and as a transitional shim during the locale split.
         */
        private const val LEGACY_PATH = "harmful_queries.json"

        /**
         * Zero-width / format characters stripped before matching: ZWSP (U+200B), ZWNJ
         * (U+200C), ZWJ (U+200D), word joiner (U+2060), BOM/ZWNBSP (U+FEFF), soft hyphen
         * (U+00AD). Injecting these mid-phrase is a common way to break substring matching.
         */
        private val ZERO_WIDTH_CHARS = Regex("[\u200B-\u200D\u2060\uFEFF\u00AD]")
    }
}

/**
 * JSON structure of `assets/harmful_queries/<localeTag>.json` (and the legacy
 * `assets/harmful_queries.json`).
 *
 * @Keep: without this, R8 in release builds strips or renames this type, and
 * Gson's reflection-based deserialization returns an empty object — flipping
 * [QueryBlocklist.loadFailed] to `true` and fail-closing every query chokepoint.
 */
@Keep
private data class HarmfulQueriesJson(
    @com.google.gson.annotations.SerializedName("class_a_terms")
    val classATerms: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("self_signal_terms")
    val selfSignalTerms: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("regex_patterns")
    val regexPatterns: List<String> = emptyList()
)
