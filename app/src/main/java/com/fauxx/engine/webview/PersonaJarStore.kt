package com.fauxx.engine.webview

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-persona cookie jars and site storage (issue #242).
 *
 * ## Why this exists
 *
 * [PhantomWebViewPool] used to document that "per-instance cookie jars are not an Android
 * primitive (`CookieManager` is process-global)". That is true of [CookieManager.getInstance]
 * and false of WebView as a whole: `androidx.webkit`'s multi-profile API gives each named
 * profile its own `CookieManager`, `WebStorage`, `ServiceWorkerController` and
 * `GeolocationPermissions`, in one process, with no extra permission. Binding a profile per
 * persona is what makes "each persona is a separate browsing identity" a true statement about
 * storage rather than an aspiration.
 *
 * This is storage only. Every persona still browses from the same handset and the same network
 * address, over one canvas raster, one WebGL stack, one font set and one screen. A tracker that
 * fingerprints rather than reads cookies can still tell they are one device, and nothing an
 * unprivileged Android app can do changes that.
 *
 * ## Feature gating
 *
 * `MULTI_PROFILE` is an `ApiFeature.NoFramework` capability, so support is a property of the
 * WebView APK installed on the device, NOT of the OS API level. [isSupported] must therefore be
 * consulted at runtime on every device rather than assumed from `Build.VERSION`. Where it is
 * absent, callers fall back to the process-global jar and the app keeps working with the old
 * shared-storage behaviour, which is why every method here degrades instead of throwing.
 *
 * ## Jar naming
 *
 * The profile name is a hash rather than the persona id. WebView persists profile names in its
 * own pref store in plaintext inside the app sandbox but outside SQLCipher, and profile
 * directories are enumerable by anything with filesystem access to the app data dir. A hashed
 * name discloses nothing about which persona owned it. It still discloses how many jars exist,
 * which [deleteJarsExcept] bounds to the live set.
 */
@Singleton
class PersonaJarStore @Inject constructor() {

    /**
     * Whether this device's WebView supports multi-profile.
     *
     * Resolved once and cached: the installed WebView APK cannot change without restarting the
     * process, and [WebViewFeature.isFeatureSupported] is not free.
     */
    val isSupported: Boolean by lazy {
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrElse {
            Timber.w(it, "MULTI_PROFILE support check threw; treating as unsupported")
            false
        }
        // Log the tier exactly once per process. Without this there is no way to tell a device
        // that got per-persona jars from one that silently fell back to the shared jar, and the
        // runCatching above means a failure is invisible. That matters most in a minified build,
        // where a broken reflective boundary would degrade every persona with no other symptom.
        if (supported) {
            Timber.i("Persona jars ACTIVE: per-persona cookie and storage isolation in use")
        } else {
            Timber.i(
                "Persona jars UNAVAILABLE: this WebView has no MULTI_PROFILE support, " +
                    "all personas share one jar"
            )
        }
        supported
    }

    /**
     * Stable jar name for a persona. Deterministic, so the same persona resolves to the same jar
     * across process restarts and its accumulated storage survives them.
     */
    fun jarKeyFor(personaId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$JAR_NAMESPACE|$personaId".toByteArray())
        return JAR_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(JAR_KEY_HEX_LENGTH)
    }

    /**
     * Bind [webView] to [jarKey], returning true when the WebView really is on that jar.
     *
     * MUST be called before the WebView navigates or runs any script.
     * `WebViewCompat.setProfile` throws [IllegalStateException] if the WebView has already
     * navigated, has had `evaluateJavascript` called on it, has had a profile set once already,
     * or has been destroyed. A WebView cannot be re-profiled, which is why rotation rebuilds the
     * pool rather than re-binding it in place.
     *
     * Returns false (rather than throwing) when multi-profile is unsupported or the bind fails,
     * leaving the WebView on the process-global default jar. A crawl on the shared jar is a
     * degraded identity story; a crash here would take out the whole engine.
     */
    fun bind(webView: WebView, jarKey: String): Boolean {
        if (!isSupported) return false
        return runCatching {
            WebViewCompat.setProfile(webView, jarKey)
            true
        }.getOrElse {
            Timber.w(it, "setProfile($jarKey) failed; falling back to the shared jar")
            false
        }
    }

    /**
     * The [CookieManager] backing [jarKey], or the process-global one when multi-profile is
     * unavailable.
     *
     * Callers must route every cookie operation through this rather than
     * [CookieManager.getInstance]. Third-party cookie acceptance in particular is configured per
     * manager, so configuring the global one while the WebView reads a profile-scoped one would
     * stop tracker accumulation silently, with no crash and no log line.
     *
     * MUST be called on the main thread. [ProfileStore.getInstance] throws
     * `IllegalStateException("Must be called on the UI thread.")` from Chromium's ThreadUtils,
     * despite [androidx.webkit.Profile]'s own methods being annotated `@AnyThread`. Off the main
     * thread this would fall through to the shared jar, which is precisely the silent failure
     * this class exists to prevent. Verified on WebView 134; every call site is inside a
     * `withContext(Dispatchers.Main)` block.
     */
    @androidx.annotation.UiThread
    fun cookieManagerFor(jarKey: String?): CookieManager {
        if (!isSupported || jarKey == null) return CookieManager.getInstance()
        return runCatching { ProfileStore.getInstance().getOrCreateProfile(jarKey).cookieManager }
            .getOrElse {
                Timber.w(it, "cookieManager for $jarKey unavailable; using the shared jar")
                CookieManager.getInstance()
            }
    }

    /**
     * Delete every jar except [keep], returning how many were removed.
     *
     * Storage is bounded by this, not by a quota. Personas are sequential and never recur, so a
     * jar whose persona is neither current nor still phasing out is dead weight that would
     * otherwise accumulate one jar per rotation forever.
     *
     * `deleteProfile` throws if the jar has live WebViews or is merely loaded in memory, and
     * throws a different exception for the default jar, so each delete is guarded individually
     * and a failure is left for the next sweep rather than aborting the rest.
     *
     * Hops to the main thread itself rather than trusting callers. [ProfileStore.getInstance]
     * requires the UI thread, and the natural caller here is the engine loop on
     * [Dispatchers.IO]: called from there this silently returned 0 on every rotation, so retired
     * jars accumulated forever with nothing in the logs to say so. Found by the on-device gate,
     * not by any unit test, because Robolectric never reaches this code.
     */
    suspend fun deleteJarsExcept(keep: Set<String>): Int {
        // Check support BEFORE hopping threads. Two reasons, and the second is not cosmetic:
        // there is nothing to sweep on an unsupported device, and dispatching anyway deadlocks
        // any caller that is itself blocking the main looper. Robolectric runs tests ON the main
        // thread, so `runBlocking { deleteJarsExcept(...) }` hung the entire unit suite.
        if (!isSupported) return 0
        return sweepOnMain(keep)
    }

    /**
     * The sweep proper. Uses [Dispatchers.Main.immediate] rather than [Dispatchers.Main]: when the
     * caller is already on the main thread this runs inline instead of posting to a looper the
     * caller may be blocking, which removes that deadlock class rather than merely avoiding it.
     */
    private suspend fun sweepOnMain(keep: Set<String>): Int = withContext(Dispatchers.Main.immediate) {
        val store = runCatching { ProfileStore.getInstance() }.getOrElse {
            Timber.w(it, "ProfileStore unavailable; skipping jar sweep")
            return@withContext 0
        }
        val names = runCatching { store.allProfileNames }.getOrElse {
            Timber.w(it, "allProfileNames failed; skipping jar sweep")
            return@withContext 0
        }
        var deleted = 0
        for (name in names) {
            if (name in keep || name == DEFAULT_PROFILE_NAME || !name.startsWith(JAR_PREFIX)) continue
            runCatching { store.deleteProfile(name) }
                .onSuccess { if (it) deleted++ }
                .onFailure { Timber.d("Jar $name not deletable yet (${it.javaClass.simpleName})") }
        }
        if (deleted > 0) Timber.d("Swept $deleted retired persona jar(s)")
        deleted
    }

    private companion object {
        /**
         * Versioned so a future change to what a jar holds can be rolled out by bumping the
         * namespace, which retires every old jar through the ordinary sweep.
         */
        const val JAR_NAMESPACE = "fauxx-jar-v1"

        /** Marks a profile as ours, so the sweep never touches a jar it did not create. */
        const val JAR_PREFIX = "p"

        const val JAR_KEY_HEX_LENGTH = 12

        /** WebView's own default profile, which `deleteProfile` refuses and we must skip. */
        const val DEFAULT_PROFILE_NAME = "Default"
    }
}
