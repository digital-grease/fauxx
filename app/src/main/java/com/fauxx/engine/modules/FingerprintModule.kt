package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
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
 * On each action, a new Android-Chromium User-Agent is pushed to [PhantomWebViewPool]
 * so that subsequent WebView requests use the rotated UA string. The UA is constrained
 * to Android-Chromium strings because the System WebView's TLS handshake is always
 * Android-Chromium; a mismatched UA over that handshake is the leak issue #168 closes.
 */
@Singleton
class FingerprintModule @Inject constructor(
    private val userAgentPool: UserAgentPool,
    private val webViewPool: PhantomWebViewPool,
    private val profileRepo: PoisonProfileRepository
) : Module {

    override suspend fun start() {
        // Seed the WebView pool with an initial Android-Chromium UA on start
        webViewPool.setUserAgent(userAgentPool.randomChromiumAndroid())
        Timber.d("FingerprintModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().fingerprintEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val ua = userAgentPool.randomChromiumAndroid()
        webViewPool.setUserAgent(ua)
        return ActionLogEntity(
            actionType = ActionType.FINGERPRINT_ROTATE,
            category = category,
            detail = "UA rotated: ${ua.take(80)}…",
            metadata = LogMetadata.toJson(LogMetadata.USER_AGENT to ua)
        )
    }
}
