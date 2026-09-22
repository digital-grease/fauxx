package com.fauxx.engine.webview

/**
 * Supplies the persona identity that [PhantomWebViewPool] should be storing under (issue #242).
 *
 * Exists so the pool can resolve the active persona WITHOUT importing `targeting.layer3`, keeping
 * the webview package free of a dependency on the targeting stack. The implementation lives
 * alongside Layer 3 and is bound in DI.
 *
 * ## Why the pool owns this rather than a module
 *
 * Per-persona storage isolation used to be bound by [com.fauxx.engine.modules.FingerprintModule],
 * which was the wrong owner twice over. Five modules share the pool (SearchPoison,
 * CookieSaturation, AdPollution, AppSignal and Fingerprint), the four that actually accumulate
 * tracker cookies are NOT Fingerprint, and each module has its own independent enable toggle. So
 * turning Fingerprint off left the four browsing modules crawling on a single shared jar with
 * the isolation silently gone. Worse, a disabled module is never dispatched at all, so no amount
 * of ungating inside that module could have fixed it.
 *
 * The jar is a property of the pool, so the pool resolves it. Any module, including one added
 * later, gets the correct jar by construction rather than by remembering to ask for it.
 */
interface PhantomIdentityProvider {

    /**
     * Id of the persona the pool should currently be storing under, or null when Layer 3 is off
     * or no persona has been generated yet. Null leaves the pool on the shared jar.
     */
    fun activePersonaId(): String?

    /**
     * Ids of every persona whose jar must be preserved: the current one, plus any still being
     * served on a channel inside its adoption lag. Anything outside this set is safe to retire.
     */
    fun livePersonaIds(): Set<String>

    /** No persona binding at all. Leaves the pool on the shared jar; used by tests and fakes. */
    companion object {
        val NONE: PhantomIdentityProvider = object : PhantomIdentityProvider {
            override fun activePersonaId(): String? = null
            override fun livePersonaIds(): Set<String> = emptySet()
        }
    }
}
