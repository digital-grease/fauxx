package com.fauxx.targeting.layer0

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer1.SelfReportLayer
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/** Layer 0 as a real-world popularity prior over categories (#322 item 8). */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryPopularityLayerTest {

    private val shipped: Map<CategoryPool, Double> by lazy {
        val asset = File("src/main/assets/${CategoryPopularityLayer.ASSET_PATH}")
        assertTrue("run from the app module root", asset.exists())
        CategoryPopularityLayer.parseShares(asset.readText())
    }
    private val prior by lazy { CategoryPopularityLayer.weightsFrom(shipped) }

    @Test
    fun `weights are the square root of the shares, averaging 1`() {
        val weights = CategoryPopularityLayer.weightsFrom(
            CategoryPool.values().associateWith { if (it == CategoryPool.MUSIC) 0.4 else 0.1 }
        )
        assertEquals(1.0, weights.values.map { it.toDouble() }.average(), 1e-5)
        assertEquals(sqrt(4.0), (weights[CategoryPool.MUSIC]!! / weights[CategoryPool.LEGAL]!!).toDouble(), 1e-5)
    }

    @Test
    fun `a tiny share is held at the minimum, a missing category weighs 1, and no shares weigh evenly`() {
        val weights = CategoryPopularityLayer.weightsFrom(mapOf(CategoryPool.MUSIC to 1.0, CategoryPool.LEGAL to 1e-9))
        assertEquals(CategoryPopularityLayer.MIN_WEIGHT, weights[CategoryPool.LEGAL]!!, 0f)
        assertEquals(1f, weights[CategoryPool.PETS]!!, 0f)
        assertEquals(CategoryPopularityLayer.EVEN, CategoryPopularityLayer.weightsFrom(emptyMap()))
    }

    @Test
    fun `unknown names and non-positive shares are dropped, and an unreadable asset weighs evenly`() {
        val shares = CategoryPopularityLayer.parseShares("""{"shares": {"MUSIC": 0.5, "NOT_A_CATEGORY": 0.3, "PETS": 0, "LEGAL": -1}}""")
        assertEquals(mapOf(CategoryPool.MUSIC to 0.5), shares)
        assertEquals(CategoryPopularityLayer.EVEN, CategoryPopularityLayer { error("unreadable") }.weights)
        assertEquals(
            CategoryPopularityLayer.EVEN,
            CategoryPopularityLayer { CategoryPopularityLayer.weightsFrom(CategoryPopularityLayer.parseShares("not json")) }.weights,
        )
    }

    @Test
    fun `the shipped asset covers every category, in the measured order, without needing the minimum`() {
        assertEquals(CategoryPool.values().toSet(), shipped.keys)
        assertEquals(1.0, shipped.values.sum(), 1e-4)
        assertEquals(shipped.keys.sortedByDescending { shipped[it] }, prior.keys.sortedByDescending { prior[it] })
        val raw = shipped.mapValues { sqrt(it.value) }
        val lowest = raw.values.min() / raw.values.average()
        assertTrue("the data alone stays above the minimum: $lowest", lowest > CategoryPopularityLayer.MIN_WEIGHT)
        val range = prior.values.max() / prior.values.min()
        assertTrue("square root of a ~730x spread is ~27x: $range", range in 15f..40f)
        assertTrue(prior.getValue(CategoryPool.ENTERTAINMENT) > prior.getValue(CategoryPool.AGRICULTURE))
    }

    @Test
    fun `the shipped asset lists every industry under one category at most`() {
        val industries = JsonParser.parseString(File("src/main/assets/${CategoryPopularityLayer.ASSET_PATH}").readText())
            .asJsonObject.getAsJsonObject("industries")
        val all = industries.entrySet().flatMap { (_, list) -> list.asJsonArray.map { it.asString } }
        assertEquals(all.size, all.toSet().size)
        assertEquals(CategoryPool.values().map { it.name }.toSet(), industries.keySet())
    }

    @Test
    fun `with any persona, the prior alone pins no category to the normalizer's floor`() {
        val random = Random(322)
        val floor = WeightNormalizer.MIN_WEIGHT
        repeat(500) {
            val interests = CategoryPool.values().toList().shuffled(random).take(3).toSet()
            val persona = PersonaRotationLayer.weightsFor(interests)
            val mix = WeightNormalizer().normalizeComplete(CategoryPool.values().associateWith { prior.getValue(it) * persona.getValue(it) })
            val lowest = mix.values.min()
            assertTrue("persona $interests: lowest share $lowest is at the floor $floor", lowest > floor * 1.5f)
        }
    }

    @Test
    fun `the engine's baseline is the prior, and a persona's interests stand out against it by the same factor`() {
        val interests = setOf(CategoryPool.AGRICULTURE, CategoryPool.ENTERTAINMENT, CategoryPool.PETS)
        val engine = engine(layer3 = PersonaRotationLayer.weightsFor(interests))
        val baseline = engine.baseline.value
        assertEquals(WeightNormalizer().normalizeComplete(prior), baseline)

        val lift = CategoryPool.values().associateWith { engine.cachedWeights.value.getValue(it) / baseline.getValue(it) }
        val interestLifts = interests.map { lift.getValue(it) }
        assertEquals("rare and popular interests lift alike", interestLifts.min(), interestLifts.max(), 1e-3f)
        val others = CategoryPool.values().filterNot { it in interests }.map { lift.getValue(it) }
        assertEquals("and so do the other categories", others.min(), others.max(), 1e-3f)
        assertEquals(PersonaRotationLayer.weightsFor(interests).let { it.getValue(CategoryPool.PETS) / it.getValue(CategoryPool.MUSIC) }, interestLifts.min() / others.min(), 1e-3f)
    }

    @Test
    fun `with every layer off, the engine draws by popularity`() {
        val engine = engine(layer3 = null)
        assertEquals(engine.baseline.value, engine.cachedWeights.value)
        assertTrue(engine.cachedWeights.value.getValue(CategoryPool.ENTERTAINMENT) > 0.1f)
    }

    private fun engine(layer3: Map<CategoryPool, Float>?): TargetingEngine {
        val neutral = CategoryPool.values().associateWith { 1f }
        val l1: SelfReportLayer = mockk { every { getWeights() } returns flowOf(neutral) }
        val l2: AdversarialScraperLayer = mockk(relaxed = true) { every { getWeights() } returns flowOf(neutral) }
        val l3: PersonaRotationLayer = mockk(relaxed = true) { every { getWeights() } returns flowOf(layer3 ?: neutral) }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return TargetingEngine(CategoryPopularityLayer { prior }, l1, l2, l3, WeightNormalizer(), scope).apply {
            setLayer3Enabled(layer3 != null)
        }
    }
}
