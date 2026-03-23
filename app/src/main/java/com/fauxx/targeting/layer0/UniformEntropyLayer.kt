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
