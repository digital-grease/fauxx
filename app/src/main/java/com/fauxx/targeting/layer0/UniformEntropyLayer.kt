package com.fauxx.targeting.layer0

import com.fauxx.data.querybank.CategoryPool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 0 of the Demographic Distancing Engine — always active, zero user data required.
 *
 * Returns weight 1.0 for every [CategoryPool] value, providing a uniform baseline that other
 * layers multiply against. Ensures no bias is introduced when higher layers are disabled.
 *
 * E9 (#176) note: because the engine combine is multiplicative and the result is
 * normalized, a constant emitted here is normalization-invariant PROVIDED it keeps
 * every combined product at or above [com.fauxx.targeting.WeightNormalizer.MIN_WEIGHT]
 * — the normalizer clamps RAW weights to that floor before summing, which breaks pure
 * scale-invariance once a small constant pushes products under it (the worst-case
 * production stack, L1 close 0.15 x L2 sticky 0.02 x L3 misaligned 0.345, sits only
 * ~3.5% above the floor). Do NOT repurpose this constant as a damping knob. The
 * "reduce Layer 0's uniform pull" lever lives where the uniform component actually
 * enters the final shape: the UNIFORM_BASELINE_WEIGHT blend term in
 * [com.fauxx.targeting.layer3.PersonaRotationLayer]. This layer stays at the
 * multiplicative identity.
 */
@Singleton
class UniformEntropyLayer @Inject constructor() {

    /**
     * Emits a map of weight 1.0 for every category. This is a constant flow since Layer 0
     * never changes.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> =
        flowOf(CategoryPool.values().associateWith { 1.0f })
}
