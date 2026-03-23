package com.fauxx.engine.modules

import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.querybank.CategoryPool

/**
 * Common interface implemented by every poison module.
 *
 * The [category] parameter on [onAction] comes from [com.fauxx.engine.scheduling.ActionDispatcher]'s
 * weighted sampling, ensuring each action targets a category aligned with the current
 * TargetingEngine weight distribution.
 */
interface Module {
    /** Start the module (allocate resources, warm up WebViews, etc.). */
    suspend fun start()

    /** Stop the module and release resources. */
    suspend fun stop()

    /** Whether this module is currently enabled in the active [com.fauxx.data.model.PoisonProfile]. */
    fun isEnabled(): Boolean

    /**
     * Execute a single action targeting [category].
     * Must be called after [start].
     * Returns a pre-filled [ActionLogEntity] that the engine will persist (write-ahead).
     *
     * @param category The content category to target, as selected by ActionDispatcher.
     * @return Log entry describing the action. Engine persists this before execution.
     */
    suspend fun onAction(category: CategoryPool): ActionLogEntity
}
