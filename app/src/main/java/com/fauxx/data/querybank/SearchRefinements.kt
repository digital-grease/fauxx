package com.fauxx.data.querybank

import com.fauxx.locale.SupportedLocale
import kotlin.random.Random

/**
 * E5 (#175): localized search-refinement templates for intent-chain sessions.
 *
 * A session starts from a goal query and narrows it the way real searchers do —
 * "trail running shoes" -> "trail running shoes reviews" -> "best trail running shoes".
 * Templates wrap the session's goal (occasionally a lightly reformulated version of
 * it) so every refinement stays topically related. Safety does not depend on the
 * transformation: EVERY refined text re-passes the QueryBlocklist dispatch gate in
 * SearchPoisonModule before anything is dispatched.
 *
 * This is dispatched, user-visible corpus (queries land on real SERPs): keep entries
 * generic, benign, commercial-intent shaped, and locale-correct. The es/fr/ru entries
 * follow the same maintainer-review expectation as the localized query banks.
 */
object SearchRefinements {

    private val TEMPLATES: Map<SupportedLocale, List<String>> = mapOf(
        SupportedLocale.EN to listOf(
            "best %s", "%s reviews", "%s price", "%s near me",
            "how to choose %s", "%s for beginners", "is %s worth it", "%s alternatives",
            "%s comparison", "%s buying guide", "cheap %s", "%s deals",
            "top rated %s", "%s recommendations", "%s pros and cons", "%s tips",
        ),
        SupportedLocale.ES to listOf(
            "mejor %s", "%s opiniones", "%s precio", "%s cerca de mí",
            "cómo elegir %s", "%s para principiantes", "%s alternativas",
            "%s comparativa", "%s barato", "%s ofertas", "%s recomendaciones",
        ),
        SupportedLocale.FR to listOf(
            "meilleur %s", "%s avis", "%s prix", "%s près de chez moi",
            "comment choisir %s", "%s pour débutants", "%s alternatives",
            "%s comparatif", "%s pas cher", "%s promotions", "%s recommandations",
        ),
        SupportedLocale.RU to listOf(
            "лучший %s", "%s отзывы", "%s цена", "%s рядом со мной",
            "как выбрать %s", "%s для начинающих", "%s аналоги",
            "%s сравнение", "%s недорого", "%s скидки", "%s рекомендации",
        ),
    )

    /** Chance that a refinement reformulates (drops an edge word of) a 3+-word goal. */
    private const val REFORMULATE_FRACTION = 0.30f

    /**
     * Build [count] distinct in-topic refinements of [goal] for [locale]. Each output
     * contains the goal — or, ~30% of the time for 3+-word goals, the goal minus one
     * edge word, since a chain whose every query embeds the seed verbatim under a
     * small fixed affix set is itself a clusterable fingerprint for the search engine.
     * Falls back to the EN templates for an unmapped locale.
     */
    fun refine(goal: String, locale: SupportedLocale, count: Int, random: Random): List<String> {
        val templates = TEMPLATES[locale] ?: TEMPLATES.getValue(SupportedLocale.EN)
        return templates.shuffled(random)
            .take(count.coerceIn(0, templates.size))
            .map { it.replace("%s", refinementCore(goal, random)) }
    }

    private fun refinementCore(goal: String, random: Random): String {
        val words = goal.split(' ').filter { it.isNotBlank() }
        if (words.size < 3 || random.nextFloat() >= REFORMULATE_FRACTION) return goal
        return if (random.nextBoolean()) {
            words.drop(1).joinToString(" ")
        } else {
            words.dropLast(1).joinToString(" ")
        }
    }
}
