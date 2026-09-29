package com.fauxx.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Curated pool of real User-Agent strings loaded from assets/user_agents.json.
 * Covers Chrome, Firefox, Samsung Browser across a range of Android versions.
 *
 * Only reached when Layer 3 is off. With a persona active, the per-persona device identity
 * (#242) supplies the User-Agent, so this pool is the no-persona fallback rather than the
 * primary source. The custom-UA override that used to live here was retired in #201: a bare
 * UA could not carry matching screen and navigator values, so it contradicted the device it
 * claimed to be.
 */
@Singleton
class UserAgentPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val random: Random = Random.Default,
) {
    private val agents: List<String> by lazy { loadAgents() }

    /**
     * A User-Agent from the bundled pool, or [DEFAULT_UA] if the asset failed to load.
     * Only reached when Layer 3 is off; with a persona active the device identity (#242)
     * supplies the UA instead.
     */
    fun random(): String = if (agents.isNotEmpty()) agents.random(random) else DEFAULT_UA

    /**
     * Returns an Android-Chromium-family User-Agent (Chrome or Samsung Browser on
     * Android) for the WebView path. The System WebView always performs an
     * Android-Chromium TLS handshake, so a non-Chromium UA here would recreate the
     * "Chrome UA over non-Chrome TLS" contradiction that issue #168 closes. Falls back to
     * [DEFAULT_UA] (a Pixel Chrome UA).
     */
    fun randomChromiumAndroid(): String =
        if (chromiumAndroidAgents.isNotEmpty()) chromiumAndroidAgents.random(random) else DEFAULT_UA

    private val chromiumAndroidAgents: List<String> by lazy { agents.filter { isChromiumAndroid(it) } }

    private fun loadAgents(): List<String> {
        return try {
            val json = context.assets.open("user_agents.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load user_agents.json")
            listOf(DEFAULT_UA)
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * Android + Chrome/Samsung only; excludes Firefox, Opera, Edge, and iOS browsers. A custom
         * UA is honored on the WebView path only when this holds (the System WebView always does an
         * Android-Chromium TLS handshake, so a non-Chromium UA there recreates the #168 tell). The
         * Settings screen uses this to warn that a non-Chromium custom UA will be ignored (#201).
         */
        internal fun isChromiumAndroid(ua: String): Boolean =
            ua.contains("Android") && ua.contains("Chrome/") &&
                !ua.contains("Firefox") && !ua.contains("OPR/") &&
                !ua.contains("EdgA") && !ua.contains("CriOS") && !ua.contains("FxiOS")
    }
}
