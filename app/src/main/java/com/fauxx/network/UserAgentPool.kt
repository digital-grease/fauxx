package com.fauxx.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UserAgentPool"

/**
 * Curated pool of real User-Agent strings loaded from assets/user_agents.json.
 * Covers Chrome, Firefox, Samsung Browser across a range of Android versions.
 */
@Singleton
class UserAgentPool @Inject constructor(
    private val context: Context
) {
    private val agents: List<String> by lazy { loadAgents() }

    /** Return a random User-Agent string from the pool. */
    fun random(): String = if (agents.isNotEmpty()) agents.random() else DEFAULT_UA

    private fun loadAgents(): List<String> {
        return try {
            val json = context.assets.open("user_agents.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user_agents.json", e)
            listOf(DEFAULT_UA)
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
