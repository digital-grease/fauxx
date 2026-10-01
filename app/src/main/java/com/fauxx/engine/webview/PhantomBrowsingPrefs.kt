package com.fauxx.engine.webview

/**
 * User preferences [PhantomWebViewPool] applies on every acquire. An interface, like
 * [PhantomIdentityProvider], so `engine/webview` does not depend on the profile repository and
 * tests can hand the pool a fixed answer.
 */
interface PhantomBrowsingPrefs {

    /** Whether synthetic page loads fetch images (see `PoisonProfile.loadImages`). */
    fun loadImages(): Boolean

    companion object {
        /** Images off, the historical default. Used by tests and fakes. */
        val NONE: PhantomBrowsingPrefs = object : PhantomBrowsingPrefs {
            override fun loadImages(): Boolean = false
        }
    }
}
