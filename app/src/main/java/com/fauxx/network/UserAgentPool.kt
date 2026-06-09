package com.fauxx.network

import android.content.Context
import com.fauxx.engine.PoisonProfileRepository
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
 * Issue #7: when the user has set a custom User-Agent in Settings, [random]
 * returns that string instead of picking from the pool — used when the user
 * wants synthetic traffic to match their real browser's UA so the noise
 * blends with their actual activity rather than being filterable as
 * UA-rotating bot traffic.
 */
@Singleton
class UserAgentPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepo: PoisonProfileRepository,
    private val random: Random = Random.Default,
) {
    private val agents: List<String> by lazy { loadAgents() }

    /**
     * Returns a User-Agent string. Honors `PoisonProfile.customUserAgent` when
     * the user has set one (Settings → "Use my own User-Agent"); otherwise
     * picks at random from the pool. Falls back to [DEFAULT_UA] if the pool
     * is empty (asset failed to load).
     */
    fun random(): String {
        profileRepo.getProfile().customUserAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return if (agents.isNotEmpty()) agents.random(random) else DEFAULT_UA
    }

    /**
     * Returns an Android-Chromium-family User-Agent (Chrome or Samsung Browser on
     * Android) for the WebView path. The System WebView always performs an
     * Android-Chromium TLS handshake, so applying a non-Chromium UA there would
     * recreate the "Chrome UA over non-Chrome TLS" contradiction that issue #168
     * closes. A custom UA is honored only when it is itself Chromium-on-Android;
     * otherwise a pool string is used. Falls back to [DEFAULT_UA] (a Pixel Chrome UA).
     */
    fun randomChromiumAndroid(): String {
        profileRepo.getProfile().customUserAgent
            ?.takeIf { it.isNotBlank() && isChromiumAndroid(it) }
            ?.let { return it }
        return if (chromiumAndroidAgents.isNotEmpty()) chromiumAndroidAgents.random(random) else DEFAULT_UA
    }

    /** Android + Chrome/Samsung only; excludes Firefox, Opera, Edge, and iOS browsers. */
    private fun isChromiumAndroid(ua: String): Boolean =
        ua.contains("Android") && ua.contains("Chrome/") &&
            !ua.contains("Firefox") && !ua.contains("OPR/") &&
            !ua.contains("EdgA") && !ua.contains("CriOS") && !ua.contains("FxiOS")

    private val chromiumAndroidAgents: List<String> by lazy { agents.filter(::isChromiumAndroid) }

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
    }
}
