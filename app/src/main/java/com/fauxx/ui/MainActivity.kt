package com.fauxx.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fauxx.ui.navigation.FauxxNavGraph
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity entry point for Fauxx. Hosts the Compose navigation graph.
 * Shows [com.fauxx.ui.screens.OnboardingScreen] on first launch only.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showOnboarding = !prefs.getBoolean("onboarding_completed", false)

        setContent {
            FauxxTheme {
                FauxxNavGraph(showOnboarding = showOnboarding)
            }
        }
    }
}
