package com.fauxx.targeting.layer0

import android.content.Context
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import kotlin.math.pow

/**
 * Layer 0 of the Demographic Distancing Engine: how common each category is on the real web
 * (#322 item 8). Always active, and needs no user data.
 *
 * Real interests follow a skewed popularity distribution. Noise topics drawn evenly over a
 * taxonomy were told apart from real ones with a 93.9% true-positive rate from a single
 * observation (Beugin and McDaniel, PETS 2024). So this layer weights each category by its share
 * of the web's topical browsing. The engine multiplies the layers together, so this is the base
 * that the persona (Layer 3) and the user's own layers (1 and 2) shape. Whatever they do, the
 * decoy mix keeps a real lean toward what people browse most, and a persona's interests still
 * stand out by the same factor over that base.
 *
 * The shares come from `assets/category_popularity.json`, built by
 * `scripts/build_category_popularity.py` from SimilarWeb's visits per industry, August to October
 * 2023. A category's weight is its share raised to [EXPONENT] (the square root), scaled so the
 * weights average 1.0. The measured shares span about 730x (entertainment gets 26% of topical
 * visits, retirement 0.04%). The square root keeps their order and spans about 27x. The owner
 * chose it over the full shares for two reasons:
 * - At full strength a persona with niche interests nearly disappears into the popular
 *   categories (a rural retiree's own interests fall from 35% of its draws to 11%), and several
 *   categories would sit on the normalizer's floor, which flattens the shape being matched.
 * - SimilarWeb files sites by industry, not by audience, so the audience categories come out
 *   too low (AARP counts as health, BabyCenter as children's health).
 *
 * **The floor.** [com.fauxx.targeting.WeightNormalizer] gives every category at least 0.1% of the
 * final mix. With Layer 3 alone (the default), a category outside the persona's interests gets
 * 0.345 times its weight here, out of a total between about 11 and 22. A weight of at least
 * [MIN_WEIGHT] keeps it at 0.16% or more, so this layer alone never pins a category to the floor.
 * The user's own layers can still push their real interests down to the floor, as they always
 * could.
 *
 * If the asset is missing or unreadable, every category weighs 1.0, the even baseline from
 * before #322. A category missing from the asset also gets 1.0.
 */
class CategoryPopularityLayer internal constructor(private val load: () -> Map<CategoryPool, Float>) {

    constructor(context: Context) : this({ fromAsset(context) })

    /** Each category's weight, averaging 1.0. Loaded on first use. */
    val weights: Map<CategoryPool, Float> by lazy {
        runCatching(load).getOrElse {
            Timber.w(it, "category popularity unusable, weighting categories evenly")
            EVEN
        }
    }

    /** Emits [weights] once. The asset is read in the collector's context, not the caller's. */
    fun getWeights(): Flow<Map<CategoryPool, Float>> = flow { emit(weights) }

    companion object {
        const val ASSET_PATH = "category_popularity.json"

        /** Applied to the measured shares: 0.5, the square root (the owner's choice, see the class KDoc). */
        const val EXPONENT = 0.5

        /** No category weighs less than this, so this layer alone keeps every category above the normalizer's floor. */
        const val MIN_WEIGHT = 0.1f

        /** Every category at 1.0: the even baseline from before #322. */
        val EVEN: Map<CategoryPool, Float> = CategoryPool.values().associateWith { 1f }

        /** A layer that weighs every category the same, for tests of the other layers. */
        fun even(): CategoryPopularityLayer = CategoryPopularityLayer { EVEN }

        /** The measured share of each category in an asset's `shares` object; unknown names and non-positive shares are dropped. */
        fun parseShares(json: String): Map<CategoryPool, Double> {
            val shares = JsonParser.parseString(json).asJsonObject.getAsJsonObject("shares")
            return shares.entrySet().mapNotNull { (name, value) ->
                val category = runCatching { CategoryPool.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                value.asDouble.takeIf { it > 0.0 && it.isFinite() }?.let { category to it }
            }.toMap()
        }

        /**
         * Weights from measured [shares]: each share raised to [EXPONENT], scaled so the categories
         * in [shares] average 1.0, then held at [MIN_WEIGHT] or more. A category not in [shares]
         * gets 1.0, and no shares at all gives [EVEN].
         */
        fun weightsFrom(shares: Map<CategoryPool, Double>): Map<CategoryPool, Float> {
            if (shares.isEmpty()) return EVEN
            val raised = shares.mapValues { (_, share) -> share.pow(EXPONENT) }
            val mean = raised.values.average()
            return CategoryPool.values().associateWith { category ->
                raised[category]?.let { (it / mean).toFloat().coerceAtLeast(MIN_WEIGHT) } ?: 1f
            }
        }

        private fun fromAsset(context: Context): Map<CategoryPool, Float> =
            weightsFrom(parseShares(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }))
    }
}
