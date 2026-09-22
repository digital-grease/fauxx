package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.device.DeviceDeriver
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.JarSwap
import com.fauxx.engine.webview.PersonaJarStore
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
    private val jarStore: PersonaJarStore,
) : Module {

    /**
     * Bind the active persona's device AND its cookie jar (issue #242), then retire any jar no
     * live persona owns.
     *
     * The two must move together. The jar is keyed on the same [PersonaChannel.DEVICE] persona
     * that supplies the User-Agent, so storage and device identity turn over in one instant. A
     * jar that outlived its handset model would be a contradiction a tracker reads in a single
     * pass, since a cookie cannot follow someone from a Pixel to a Galaxy.
     *
     * The sweep runs only on an actual rotation, not on every action: enumerating profiles is not
     * free, and nothing can have been retired if the jar did not change.
     */
    private suspend fun bindPersonaDevice(persona: SyntheticPersona) {
        // Jar FIRST, device second. If the swap was deferred or failed, the pool is still serving
        // the PREVIOUS persona's jar, and presenting this persona's User-Agent over it would
        // produce exactly the contradiction the feature removes: a cookie set under one handset
        // model being replayed under another. Hold the old device until the jar catches up; the
        // next fingerprint action retries. UNSUPPORTED is not a mismatch, it means no device
        // identity would ever be applied on that whole population of devices.
        val swap = webViewPool.setPersonaJar(persona.id)
        if (!swap.jarMatchesPersona) {
            Timber.d("Holding previous device identity: jar swap $swap")
            return
        }
        webViewPool.setDevice(deviceDeriver.mobileFor(persona))
        if (swap == JarSwap.SWAPPED) {
            val live = personaRotationLayer.livePersonaIds().map(jarStore::jarKeyFor).toSet()
            runCatching { jarStore.deleteJarsExcept(live) }
                .onFailure { Timber.w(it, "Persona jar sweep failed; retrying next rotation") }
        }
    }

    override suspend fun start() {
        val persona = currentPersona()
        if (persona != null) {
            bindPersonaDevice(persona)
        } else {
            // No active persona (Layer 3 off): seed a stable UA once; never churn per action.
            webViewPool.setUserAgentIfUnset(userAgentPool.randomChromiumAndroid())
        }
        Timber.d("FingerprintModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().fingerprintEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val persona = currentPersona()
        return if (persona != null) {
            // Idempotent re-assert of the persona's stable device + jar (both change only on
            // persona rotation, and setPersonaJar short-circuits on an unchanged jar).
            val device = deviceDeriver.mobileFor(persona)
            bindPersonaDevice(persona)
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
    private fun currentPersona(): SyntheticPersona? =
        personaRotationLayer.personaForChannel(PersonaChannel.DEVICE)
}
