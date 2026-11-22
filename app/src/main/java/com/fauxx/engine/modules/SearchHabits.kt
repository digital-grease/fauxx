package com.fauxx.engine.modules

import com.fauxx.data.device.DeviceDeriver
import com.fauxx.di.QueryGrammarSeed
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * How one persona searches (#322 item 4): the engine it uses for most of its searches, and how
 * often a search is refined or ends in a result click.
 *
 * A real person searches on one engine almost all the time (100% of searches in the history shared
 * in #270) and often searches without clicking anything. Before this, every session picked an engine
 * evenly from the pool and always refined and clicked, a shape no person has. The habits are drawn
 * per persona from ranges, so installs and personas do not share one fingerprint, and they stay
 * fixed for the persona's life.
 *
 * @property primaryEngine Engine id (see [SEARCH_ENGINE_IDS]) used for most searches.
 * @property primaryShare Share of searches sent to [primaryEngine] while it is usable.
 * @property refineChance Chance that a search session refines its first query at all.
 * @property clickChance Chance that a search session clicks a result.
 */
data class SearchHabits(
    val primaryEngine: String,
    val primaryShare: Float,
    val refineChance: Float,
    val clickChance: Float,
) {
    /**
     * The engine for one search among [selectable] (the engines allowed and not backing off): the
     * primary [primaryShare] of the time, otherwise another engine weighted by its market share in
     * [locale]. When the primary is not selectable (excluded, or held back after a bot check, #353),
     * every search goes to the others.
     */
    fun pickEngine(selectable: List<String>, locale: SupportedLocale, random: Random): String {
        require(selectable.isNotEmpty()) { "no selectable engine" }
        if (primaryEngine in selectable && (selectable.size == 1 || random.nextFloat() < primaryShare)) {
            return primaryEngine
        }
        return SearchEngineShares.weightedPick(selectable.filterNot { it == primaryEngine }, locale, random)
    }

    companion object {
        /**
         * Ranges each persona's values are drawn from, in per mille. The refine and click ranges
         * were tuned with the stream-shape report (StreamShapeReportTest): at MEDIUM, 54% of
         * searches end without a click and 47% are followed by another within two minutes,
         * against 55% and 43% in the #270 history.
         */
        private val PRIMARY_SHARE_PER_MILLE = 850..950
        private val REFINE_CHANCE_PER_MILLE = 150..450
        private val CLICK_CHANCE_PER_MILLE = 650..900

        private const val DOMAIN_PRIMARY = "search:primary"
        private const val DOMAIN_PRIMARY_SHARE = "search:primary-share"
        private const val DOMAIN_REFINE = "search:refine"
        private const val DOMAIN_CLICK = "search:click"

        /**
         * Derive the habits for [key] (a persona id, or an install key when Layer 3 is off).
         *
         * Uses the same portable hash as [DeviceDeriver.pick] and no PRNG, so another implementation
         * (fauxx-desktop) can derive the same habits from the same synced persona. The primary is
         * drawn from [allowedEngines] (the user's opt-outs applied, #281) weighted by market share in
         * [locale]; a temporary bot-check hold does not change it.
         */
        fun derive(key: String, locale: SupportedLocale, allowedEngines: List<String>): SearchHabits {
            val engines = allowedEngines.ifEmpty { SEARCH_ENGINE_IDS }
            val weights = engines.map { SearchEngineShares.weight(it, locale) }
            var point = DeviceDeriver.pick(key, "$DOMAIN_PRIMARY:${locale.tag}", 0, weights.sum())
            var primary = engines.last()
            for ((engine, weight) in engines.zip(weights)) {
                if (point < weight) {
                    primary = engine
                    break
                }
                point -= weight
            }
            return SearchHabits(
                primaryEngine = primary,
                primaryShare = fraction(key, DOMAIN_PRIMARY_SHARE, PRIMARY_SHARE_PER_MILLE),
                refineChance = fraction(key, DOMAIN_REFINE, REFINE_CHANCE_PER_MILLE),
                clickChance = fraction(key, DOMAIN_CLICK, CLICK_CHANCE_PER_MILLE),
            )
        }

        private fun fraction(key: String, domain: String, perMille: IntRange): Float {
            val span = perMille.last - perMille.first + 1
            return (perMille.first + DeviceDeriver.pick(key, domain, 0, span)) / 1000f
        }
    }
}

/**
 * Search-engine market share per noise language, in per mille, for the engines in Fauxx's pool.
 *
 * Rounded from StatCounter's June 2026 all-platform figures for the country each language's search
 * parameters target ([SupportedLocale.defaultRegion]): the United States, Spain, France and Russia.
 * Engines outside the pool (Ecosia, Qwant, Mail.ru) are left out, so the rows do not sum to 1000;
 * only the ratios matter. An engine too small to show gets 1, so it stays possible but rare.
 *
 * Network observers see which engine a search goes to. Weighting by real usage makes the noise go
 * where the user's own searches most likely go, so the engine does not separate the two.
 */
internal object SearchEngineShares {
    private val SHARES: Map<SupportedLocale, Map<String, Int>> = mapOf(
        SupportedLocale.EN to mapOf("google" to 867, "bing" to 87, "yahoo" to 26, "duckduckgo" to 15, "yandex" to 3),
        SupportedLocale.ES to mapOf("google" to 924, "bing" to 40, "yahoo" to 27, "duckduckgo" to 5, "yandex" to 1),
        SupportedLocale.FR to mapOf("google" to 884, "bing" to 57, "yahoo" to 23, "duckduckgo" to 7, "yandex" to 1),
        SupportedLocale.RU to mapOf("yandex" to 710, "google" to 265, "bing" to 10, "duckduckgo" to 3, "yahoo" to 1),
    )

    /** Share of [engine] in [locale]; 1 for an engine the table does not know. */
    fun weight(engine: String, locale: SupportedLocale): Int =
        (SHARES[locale] ?: SHARES.getValue(SupportedLocale.EN))[engine]?.coerceAtLeast(1) ?: 1

    /** One of [engines], weighted by [weight]. */
    fun weightedPick(engines: List<String>, locale: SupportedLocale, random: Random): String {
        require(engines.isNotEmpty()) { "no engine to pick from" }
        val weights = engines.map { weight(it, locale) }
        var point = random.nextInt(weights.sum())
        for ((engine, weight) in engines.zip(weights)) {
            if (point < weight) return engine
            point -= weight
        }
        return engines.last()
    }
}

/**
 * Supplies the active [SearchHabits] to [SearchPoisonModule]. [NONE] means no habits: every search
 * keeps the old shape (an even engine pick, always refined, always clicked), which the module's own
 * tests rely on.
 */
fun interface SearchHabitsSource {
    /** The habits for searches in [locale] over the user's [allowedEngines], or null for none. */
    fun habits(locale: SupportedLocale, allowedEngines: List<String>): SearchHabits?

    companion object {
        val NONE = SearchHabitsSource { _, _ -> null }
    }
}

/**
 * Habits from the active persona, through its own staggered [PersonaChannel.SEARCH] so a rotation
 * does not switch engines in the same instant as everything else. With Layer 3 off there is no
 * persona, so the habits come from the per-install seed instead and stay fixed for the install.
 */
@Singleton
class PersonaSearchHabits @Inject constructor(
    private val personaLayer: PersonaRotationLayer,
    private val installSeed: QueryGrammarSeed,
) : SearchHabitsSource {
    /** Resolved on first use, which is a search on the engine's background dispatcher. */
    private val installKey by lazy { "install:${installSeed.load()}" }

    override fun habits(locale: SupportedLocale, allowedEngines: List<String>): SearchHabits {
        val key = personaLayer.personaForChannel(PersonaChannel.SEARCH)?.id ?: installKey
        return SearchHabits.derive(key, locale, allowedEngines)
    }
}
