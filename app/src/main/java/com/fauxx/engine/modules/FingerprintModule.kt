package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.device.DeviceDeriver
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.network.UserAgentPool
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presents the active persona's STABLE mobile device on the WebView (issue #242).
 *
 * This is no longer a per-action User-Agent randomizer. A persona owns one coherent Android-Chromium
 * device identity ([DeviceProfile], derived by [DeviceDeriver]) for its whole life, so the UA the
 * WebView presents is stable and only changes when the persona (and thus the device) rotates — phased
 * in through the staggered [PersonaChannel.DEVICE] accessor so it does not flip in the same instant as
 * the other bound channels. Random per-action UA churn was itself a bot tell: a single profile
 * presenting dozens of unrelated UAs is textbook automation, which gets synthetic traffic filtered out
 * before it can poison a broker profile.
 *
 * When Layer 3 is disabled (no active persona), it seeds a single stable Android-Chromium UA once and
 * holds it, rather than rotating. The UA stays Android-Chromium because the System WebView always does
 * an Android-Chromium TLS handshake (issue #168).
 */
@Singleton
class FingerprintModule @Inject constructor(
    private val userAgentPool: UserAgentPool,
    private val webViewPool: PhantomWebViewPool,
    private val profileRepo: PoisonProfileRepository,
    private val personaRotationLayer: PersonaRotationLayer,
    private val deviceDeriver: DeviceDeriver,
) : Module {

    override suspend fun start() {
        val device = currentDevice()
        if (device != null) {
            webViewPool.setDevice(device)
        } else {
            // No active persona (Layer 3 off): seed a stable UA once; never churn per action.
            webViewPool.setUserAgentIfUnset(userAgentPool.randomChromiumAndroid())
        }
        Timber.d("FingerprintModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().fingerprintEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val device = currentDevice()
        return if (device != null) {
            // Idempotent re-assert of the persona's stable device (changes only on persona rotation).
            webViewPool.setDevice(device)
            ActionLogEntity(
                actionType = ActionType.FINGERPRINT_ROTATE,
                category = category,
                detail = "Persona device: ${device.model} — ${device.userAgent.take(72)}…",
                metadata = LogMetadata.toJson(LogMetadata.USER_AGENT to device.userAgent),
            )
        } else {
            // Layer 3 disabled: hold one stable Android-Chromium UA rather than rotating per action.
            webViewPool.setUserAgentIfUnset(userAgentPool.randomChromiumAndroid())
            ActionLogEntity(
                actionType = ActionType.FINGERPRINT_ROTATE,
                category = category,
                detail = "Fingerprint held (no active persona)",
            )
        }
    }

    /** The active persona's mobile device via the staggered DEVICE channel, or null when Layer 3 is off. */
    private fun currentDevice(): DeviceProfile? =
        personaRotationLayer.personaForChannel(PersonaChannel.DEVICE)?.let { deviceDeriver.mobileFor(it) }
}
