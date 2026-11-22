package com.fauxx.ui.viewmodels

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer0.CategoryPopularityLayer
import com.fauxx.targeting.layer3.PersonaRotationLayer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Targeting chart's colours once Layer 0 is a popularity prior (#322 item 8). */
class WeightShapingTest {

    private val normalizer = WeightNormalizer()
    private val prior = CategoryPopularityLayer.weightsFrom(
        CategoryPopularityLayer.parseShares(File("src/main/assets/${CategoryPopularityLayer.ASSET_PATH}").readText())
    )
    private val baseline = normalizer.normalizeComplete(prior)

    /** The final mix for these layer multipliers on top of the shipped prior, as the engine combines them. */
    private fun mix(vararg layers: Map<CategoryPool, Float>) = normalizer.normalizeComplete(
        CategoryPool.values().associateWith { c -> layers.fold(prior.getValue(c)) { w, layer -> w * layer.getValue(c) } }
    )

    private fun layer(default: Float, vararg set: Pair<Collection<CategoryPool>, Float>) =
        CategoryPool.values().associateWith { c -> set.firstOrNull { c in it.first }?.second ?: default }

    private fun Map<CategoryPool, WeightShaping>.of(shaping: WeightShaping) = filterValues { it == shaping }.keys

    @Test
    fun `with no layer on, popular and rare categories are both neutral`() {
        val shaping = weightShaping(baseline, baseline)
        assertEquals(CategoryPool.values().toSet(), shaping.of(WeightShaping.NEUTRAL))
    }

    @Test
    fun `a persona's interests are boosted and the rest neutral, even when its interests are the most popular`() {
        val popular = setOf(CategoryPool.ENTERTAINMENT, CategoryPool.TECHNOLOGY, CategoryPool.FINANCE)
        val rare = setOf(CategoryPool.RETIREMENT, CategoryPool.WELLNESS_ALTERNATIVE, CategoryPool.AGRICULTURE)
        for (interests in listOf(popular, rare)) {
            val shaping = weightShaping(mix(PersonaRotationLayer.weightsFor(interests)), baseline)
            assertEquals(interests, shaping.of(WeightShaping.BOOSTED))
            assertEquals(emptySet<CategoryPool>(), shaping.of(WeightShaping.SUPPRESSED))
        }
    }

    @Test
    fun `an ad profile that suppresses most categories still shows them suppressed`() {
        val confirmed = CategoryPool.values().take(20).toSet()
        val absent = CategoryPool.values().drop(20).toSet()
        // Layer 2's confirmed 0.05 and never-seen 3.0, under a persona.
        val persona = PersonaRotationLayer.weightsFor(setOf(CategoryPool.PETS, CategoryPool.MUSIC, CategoryPool.TRAVEL))
        val shaping = weightShaping(mix(layer(1f, confirmed to 0.05f, absent to 3f), persona), baseline)
        assertEquals(confirmed, shaping.of(WeightShaping.SUPPRESSED))
        assertEquals(emptySet<CategoryPool>(), shaping.of(WeightShaping.NEUTRAL).intersect(confirmed))
    }

    @Test
    fun `self-reported interests are suppressed and distant categories boosted`() {
        val close = setOf(CategoryPool.TECHNOLOGY, CategoryPool.GAMING)
        val distant = setOf(CategoryPool.RETIREMENT, CategoryPool.PARENTING)
        val shaping = weightShaping(mix(layer(0.92f, close to 0.15f, distant to 2.5f)), baseline)
        assertEquals(close, shaping.of(WeightShaping.SUPPRESSED))
        assertEquals(distant, shaping.of(WeightShaping.BOOSTED))
    }

    @Test
    fun `without a baseline, a share is compared with an even one, as before`() {
        val interests = setOf(CategoryPool.PETS, CategoryPool.MUSIC, CategoryPool.TRAVEL)
        val even = normalizer.normalizeComplete(PersonaRotationLayer.weightsFor(interests))
        val shaping = weightShaping(even, emptyMap())
        assertEquals(interests, shaping.of(WeightShaping.BOOSTED))
        assertEquals(emptySet<CategoryPool>(), shaping.of(WeightShaping.SUPPRESSED))
        assertEquals(emptyMap<CategoryPool, WeightShaping>(), weightShaping(emptyMap(), baseline))
    }
}
