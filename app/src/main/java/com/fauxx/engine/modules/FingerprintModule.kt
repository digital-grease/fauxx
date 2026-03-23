package com.fauxx.engine.modules

import android.util.Log
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.network.UserAgentPool
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FingerprintModule"

/**
 * Rotates User-Agent and injects canvas noise to reduce fingerprinting consistency.
 * The actual JS injection happens at the WebView layer via [com.fauxx.engine.webview.JSInjector].
 */
@Singleton
class FingerprintModule @Inject constructor(
    private val userAgentPool: UserAgentPool,
    private val profileRepo: PoisonProfileRepository
) : Module {

    override suspend fun start() {
        Log.d(TAG, "FingerprintModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().fingerprintEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val ua = userAgentPool.random()
        return ActionLogEntity(
            actionType = ActionType.FINGERPRINT_ROTATE,
            category = category,
            detail = "UA rotated: ${ua.take(80)}…"
        )
    }
}
