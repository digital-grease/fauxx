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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrator for the Demographic Distancing Engine.
 *
 * Combines all four targeting layers into a final normalized weight map via multiplicative
 * combination: finalWeight = L0 × L1 × L2 × L3, then normalized so all weights sum to 1.0.
 *
 * When adversarial allocation is enabled (E4 #180), a final optimization stage perturbs that
 * normalized distribution within a KL-divergence budget to suppress what a broker-inference
 * surrogate would infer about the user's real interests (see [AdversarialAllocator]). It is a
 * post-combine *stage*, not a fifth multiplicative layer, because a multiplier cannot express the
 * budget constraint the optimizer must respect.
 *
 * Exposes [getWeights] as a reactive [Flow] that recalculates automatically when any layer's
 * inputs change (user edits their profile, scraper returns new data, persona rotates) or when
 * layer enable flags are toggled.
 *
 * This is a Hilt `@Singleton` — it lives as long as the application process.
 * The internal [singletonScope] is cancelled via [close] which is called from
 * `FauxxApp.onTerminate()` and test teardown.
 */
@Singleton
class TargetingEngine private constructor(
    private val layer0: UniformEntropyLayer,
    private val layer1: SelfReportLayer,
    private val layer2: AdversarialScraperLayer,
    private val layer3: PersonaRotationLayer,
    private val normalizer: WeightNormalizer,
    private val allocator: AdversarialAllocator,
    private val singletonScope: CoroutineScope
) : Closeable {

    @Inject constructor(
        layer0: UniformEntropyLayer,
        layer1: SelfReportLayer,
        layer2: AdversarialScraperLayer,
        layer3: PersonaRotationLayer,
        normalizer: WeightNormalizer,
        allocator: AdversarialAllocator
    ) : this(
        layer0, layer1, layer2, layer3, normalizer, allocator,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    /**
     * Test constructor allowing injection of a custom scope (e.g., Dispatchers.Unconfined).
     * The allocator defaults to an empty-table instance so existing tests need no allocator wiring;
     * adversarial allocation is off by default and so never runs unless explicitly enabled.
     */
    internal constructor(
        layer0: UniformEntropyLayer,
        layer1: SelfReportLayer,
        layer2: AdversarialScraperLayer,
        layer3: PersonaRotationLayer,
        normalizer: WeightNormalizer,
        scope: CoroutineScope,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit = Unit,
        allocator: AdversarialAllocator = AdversarialAllocator(
            BrokerSurrogate(CooccurrenceTable.empty()),
            normalizer,
            ProfileDriftMetric(),
        )
    ) : this(layer0, layer1, layer2, layer3, normalizer, allocator, scope)

    private val layer1Enabled = MutableStateFlow(false)
    private val layer2Enabled = MutableStateFlow(false)
    private val layer3Enabled = MutableStateFlow(false)
    private val adversarialAllocationEnabled = MutableStateFlow(false)

    /** Default uniform weights used before first layer emission. */
    private val uniformWeights: Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { 1f / CategoryPool.values().size }

    /**
     * Cached weight [StateFlow] that recalculates only when any layer emits new data
     * or an enable flag changes.
     * Read [cachedWeights].value for the latest snapshot without Flow collection overhead.
     */
    val cachedWeights: StateFlow<Map<CategoryPool, Float>> = combine(
        layer0.getWeights(),
        layer1.getWeights(),
        layer2.getWeights(),
        layer3.getWeights(),
        layer1Enabled,
        layer2Enabled,
        layer3Enabled,
        adversarialAllocationEnabled
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val l0 = flows[0] as Map<CategoryPool, Float>
        @Suppress("UNCHECKED_CAST")
        val l1 = flows[1] as Map<CategoryPool, Float>
        @Suppress("UNCHECKED_CAST")
        val l2 = flows[2] as Map<CategoryPool, Float>
        @Suppress("UNCHECKED_CAST")
        val l3 = flows[3] as Map<CategoryPool, Float>
        val l1On = flows[4] as Boolean
        val l2On = flows[5] as Boolean
        val l3On = flows[6] as Boolean
        val allocOn = flows[7] as Boolean
        val combined = CategoryPool.values().associateWith { category ->
            val w0 = l0.getOrDefault(category, 1f)
            val w1 = if (l1On) l1.getOrDefault(category, 1f) else 1f
            val w2 = if (l2On) l2.getOrDefault(category, 1f) else 1f
            val w3 = if (l3On) l3.getOrDefault(category, 1f) else 1f
            w0 * w1 * w2 * w3
        }
        val normalized = normalizer.normalizeComplete(combined)
        if (!allocOn) {
            normalized
        } else {
            // The user's real/known interests are the categories the active suppression layers
            // pull strongly below neutral (L1 self-reported-close = 0.15, L2 confirmed = 0.05/0.02);
            // the 0.5 threshold separates those from the global 0.92 damp, neutral 1.0, and boosts.
            // Reuses existing signals only — no new inference.
            val protectedInterests = CategoryPool.values().filterTo(HashSet()) { category ->
                (l1On && l1.getOrDefault(category, 1f) < TRUE_INTEREST_THRESHOLD) ||
                    (l2On && l2.getOrDefault(category, 1f) < TRUE_INTEREST_THRESHOLD)
            }
            allocator.allocate(normalized, protectedInterests)
        }
    }.stateIn(singletonScope, SharingStarted.Eagerly, uniformWeights)

    /** Enable or disable Layer 1 (self-report). */
    fun setLayer1Enabled(enabled: Boolean) { layer1Enabled.value = enabled }

    /** Enable or disable Layer 2 (adversarial scraper). */
    fun setLayer2Enabled(enabled: Boolean) {
        layer2Enabled.value = enabled
        layer2.setEnabled(enabled)
    }

    /** Enable or disable Layer 3 (persona rotation). */
    fun setLayer3Enabled(enabled: Boolean) {
        layer3Enabled.value = enabled
        layer3.setEnabled(enabled)
    }

    /**
     * Enable or disable the adversarial allocation stage (E4 #180). Off by default; it only has an
     * effect when Layer 1 and/or Layer 2 are also enabled (they supply the protected-interest
     * signal it optimizes against).
     */
    fun setAdversarialAllocationEnabled(enabled: Boolean) {
        adversarialAllocationEnabled.value = enabled
    }

    /**
     * Returns a [Flow] emitting the current normalized weight map across all [CategoryPool] values.
     * Prefer reading [cachedWeights].value for hot-path access without Flow collection overhead.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> = cachedWeights

    /** Cancel background weight collection scope. */
    override fun close() {
        singletonScope.cancel()
    }

    private companion object {
        /**
         * Layer-weight threshold below which a category is treated as a real/known user interest.
         * Catches the strong-suppress buckets (L1 0.15, L2 0.05/0.02) while excluding the L1 global
         * 0.92 damp, neutral 1.0, and the distance/absence boosts (2.5 / 3.0).
         */
        const val TRUE_INTEREST_THRESHOLD = 0.5f
    }
}
