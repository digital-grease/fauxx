package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.network.UserAgentPool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rotates User-Agent and injects canvas noise to reduce fingerprinting consistency.
 * The actual JS injection happens at the WebView layer via [com.fauxx.engine.webview.JSInjector].
 *
 * On each action, a new random User-Agent is pushed to [PhantomWebViewPool] so that
 * subsequent WebView requests use the rotated UA string.
 */
@Singleton
class FingerprintModule @Inject constructor(
    private val userAgentPool: UserAgentPool,
    private val webViewPool: PhantomWebViewPool,
    private val profileRepo: PoisonProfileRepository
) : Module {

    override suspend fun start() {
        // Seed the WebView pool with an initial random UA on start
        webViewPool.setUserAgent(userAgentPool.random())
        Timber.d("FingerprintModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().fingerprintEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val ua = userAgentPool.random()
        webViewPool.setUserAgent(ua)
        return ActionLogEntity(
            actionType = ActionType.FINGERPRINT_ROTATE,
            category = category,
            detail = "UA rotated: ${ua.take(80)}…"
        )
    }
}
