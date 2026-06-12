package com.fauxx.targeting

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.allocation.AdversarialAllocator
import com.fauxx.targeting.allocation.BrokerSurrogate
import com.fauxx.targeting.allocation.CooccurrenceTable
import com.fauxx.targeting.layer0.UniformEntropyLayer
import com.fauxx.targeting.layer1.SelfReportLayer
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer2.ProfileDriftMetric
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the wiring of the E4 #180 adversarial allocation stage into [TargetingEngine]:
 * it is off by default, derives the protected-interest set from the L1/L2 signals, and when
 * enabled drives down what the surrogate would infer about the user's real interest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetingEngineAllocationTest {

    private val normalizer = WeightNormalizer()
    private val cats = CategoryPool.values()

    // FINANCE is the user's real interest (L1 suppresses it to 0.15); it co-occurs with
    // REAL_ESTATE and BUSINESS in the surrogate's prior, so a broker would still infer it.
    private val table = CooccurrenceTable(
        neighbors = mapOf(
            CategoryPool.FINANCE to mapOf(CategoryPool.REAL_ESTATE to 0.9, CategoryPool.BUSINESS to 0.8),
            CategoryPool.REAL_ESTATE to mapOf(CategoryPool.FINANCE to 0.9),
            CategoryPool.BUSINESS to mapOf(CategoryPool.FINANCE to 0.8),
        ),
        bias = -2.0, selfCoef = 6.0, neighborCoef = 3.0,
    )
    private val surrogate = BrokerSurrogate(table)

    private fun l1Weights(): Map<CategoryPool, Float> =
        cats.associateWith { if (it == CategoryPool.FINANCE) 0.15f else 1f }

    private fun neutral(): Map<CategoryPool, Float> = cats.associateWith { 1f }

    private fun engine(): TargetingEngine {
        val l0 = UniformEntropyLayer()
        val l1: SelfReportLayer = mockk { every { getWeights() } returns flowOf(l1Weights()) }
        val l2: AdversarialScraperLayer = mockk(relaxed = true) { every { getWeights() } returns flowOf(neutral()) }
        val l3: PersonaRotationLayer = mockk(relaxed = true) { every { getWeights() } returns flowOf(neutral()) }
        val allocator = AdversarialAllocator(surrogate, normalizer, ProfileDriftMetric())
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return TargetingEngine(l0, l1, l2, l3, normalizer, scope, Unit, allocator)
    }

    @Test
    fun `with allocation off the output is the plain heuristic combination`() = runTest {
        val engine = engine()
        try {
            engine.setLayer1Enabled(true)
            // L1 suppresses FINANCE, so its weight sits below uniform.
            assertTrue(engine.cachedWeights.value.getValue(CategoryPool.FINANCE) < 1f / cats.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `enabling allocation lowers the surrogate's inference of the protected interest`() = runTest {
        val engine = engine()
        try {
            engine.setLayer1Enabled(true)
            val before = engine.cachedWeights.value
            val beforeInfer = surrogate.infer(before).getValue(CategoryPool.FINANCE)

            engine.setAdversarialAllocationEnabled(true)
            val after = engine.cachedWeights.value
            val afterInfer = surrogate.infer(after).getValue(CategoryPool.FINANCE)

            assertTrue("distribution should change when allocation is enabled", after != before)
            assertTrue("alloc should reduce FINANCE inference: before=$beforeInfer after=$afterInfer", afterInfer < beforeInfer)
            assertEquals(1.0, after.values.sum().toDouble(), 1e-3)
        } finally {
            engine.close()
        }
    }
}
