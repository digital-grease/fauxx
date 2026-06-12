package com.fauxx.data.querybank

import com.fauxx.di.QueryGrammarSeed
import com.fauxx.locale.LocaleManager
import com.fauxx.targeting.layer3.PersonaRotationLayer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generative-grammar query model (E5 #179) that supersedes [MarkovQueryGenerator] behind the same
 * `generate(category)` surface.
 *
 * The Markov path's weakness is a fleet-level signature: every install trains the same bigram
 * statistics from the same bundled corpus, so the synthetic query distribution collapses onto a
 * shared n-gram shape a broker can fingerprint. This generator keeps the Markov path's naturalness
 * (and its custom-interest suppression) by drawing each query's HEAD from the corpus / Markov, then
 * wraps it in a per-INSTALL grammar style: a device seed fixes this install's head-source mix and
 * refinement frequency, and the active persona's commercial lean nudges the refinement style. Two
 * installs querying the same category therefore land on different refinement distributions, which
 * is what actually breaks the fleet signature — not neural-ness.
 *
 * It is fully on-device with no new dependency. Refinements reuse the editorial, localized
 * [SearchRefinements] templates. Every output is gated through [QueryBlocklist] before return, and
 * [MarkovQueryGenerator] is the retained fallback when grammar composition cannot produce a
 * permitted query.
 */
@Singleton
class GrammarQueryGenerator @Inject constructor(
    private val queryBankManager: QueryBankManager,
    private val queryBlocklist: QueryBlocklist,
    private val localeManager: LocaleManager,
    private val markovGenerator: MarkovQueryGenerator,
    private val personaLayer: PersonaRotationLayer,
    seed: QueryGrammarSeed,
    private val random: Random = Random.Default,
) {
    /** This install's grammar style, derived deterministically from the persisted device seed. */
    private val style = InstallStyle.fromSeed(seed.value)

    /**
     * Generate a compound search query for [category]. Guaranteed never to return a query that
     * matches [QueryBlocklist]: grammar composition is resampled up to [MAX_RESAMPLE_ATTEMPTS]
     * times, then falls back to the Markov path (itself blocklist-guaranteed), then to empty
     * (which [com.fauxx.engine.modules.SearchPoisonModule] suppresses).
     */
    fun generate(category: CategoryPool, targetLength: Int = random.nextInt(3, 9)): String {
        repeat(MAX_RESAMPLE_ATTEMPTS) {
            val candidate = composeOnce(category, targetLength)
            if (candidate.isNotEmpty() && !queryBlocklist.isBlocked(candidate)) return candidate
        }
        val markov = markovGenerator.generate(category, targetLength)
        return if (markov.isNotEmpty() && !queryBlocklist.isBlocked(markov)) markov else ""
    }

    /** One composition pass. May return a blocked output; the caller checks. */
    private fun composeOnce(category: CategoryPool, targetLength: Int): String {
        // Head: natural and safe. The per-install style sets how often we take a Markov head (which
        // also carries injected custom-interest suppression) vs a raw corpus phrase.
        val head = if (random.nextFloat() < style.markovHeadProbability) {
            markovGenerator.generate(category, targetLength)
        } else {
            queryBankManager.randomQuery(category)
        }
        if (head.isBlank()) return ""

        // Refinement: persona- and install-styled. The refinement RATE is per-install and nudged by
        // the active persona's commercial lean, so installs diverge in how often (and thus how) they
        // refine the same head — the per-install distribution shift that defeats the fleet signature.
        val refineRate = (style.refineProbability + personaCommercialLean() * style.personaRefineWeight)
            .coerceIn(0f, MAX_REFINE_RATE)
        if (random.nextFloat() >= refineRate) return head

        return SearchRefinements.refine(head, localeManager.currentLocale, count = 1, random)
            .firstOrNull() ?: head
    }

    /** 0f (informational persona) .. 1f (commercial persona); 0.5 when no persona/interests. */
    private fun personaCommercialLean(): Float {
        val interests = personaLayer.activePersona()?.interests ?: return NEUTRAL_LEAN
        if (interests.isEmpty()) return NEUTRAL_LEAN
        return interests.count { it in COMMERCIAL_CATEGORIES }.toFloat() / interests.size
    }

    // --- Custom-interest seed surface: delegated to the Markov fallback, which owns the seed-phrase
    // store and folds them into its bigram model, so Markov heads still carry interest suppression. ---

    fun injectSeedPhrases(category: CategoryPool, phrases: List<String>) =
        markovGenerator.injectSeedPhrases(category, phrases)

    fun clearSeedPhrases() = markovGenerator.clearSeedPhrases()

    fun clearAllState() = markovGenerator.clearAllState()

    /**
     * Per-install style derived from the device seed. The ranges are deliberately wide so two
     * installs' query distributions are visibly different even with the same corpus and persona.
     */
    private class InstallStyle(
        val markovHeadProbability: Float,
        val refineProbability: Float,
        val personaRefineWeight: Float,
    ) {
        companion object {
            fun fromSeed(seed: Long): InstallStyle {
                val r = Random(seed)
                return InstallStyle(
                    markovHeadProbability = 0.40f + r.nextFloat() * 0.50f, // 0.40 .. 0.90
                    refineProbability = 0.20f + r.nextFloat() * 0.50f,     // 0.20 .. 0.70
                    personaRefineWeight = 0.10f + r.nextFloat() * 0.30f,   // 0.10 .. 0.40
                )
            }
        }
    }

    private companion object {
        const val MAX_RESAMPLE_ATTEMPTS = 5
        const val MAX_REFINE_RATE = 0.95f
        const val NEUTRAL_LEAN = 0.5f

        /** Categories with buy/compare/price intent — bias refinements toward commercial templates. */
        val COMMERCIAL_CATEGORIES: Set<CategoryPool> = setOf(
            CategoryPool.FINANCE, CategoryPool.REAL_ESTATE, CategoryPool.AUTOMOTIVE,
            CategoryPool.FASHION, CategoryPool.BEAUTY, CategoryPool.TRAVEL,
            CategoryPool.HOME_IMPROVEMENT, CategoryPool.BUSINESS, CategoryPool.RETIREMENT,
            CategoryPool.TECHNOLOGY, CategoryPool.FITNESS, CategoryPool.PETS, CategoryPool.GAMING,
        )
    }
}
