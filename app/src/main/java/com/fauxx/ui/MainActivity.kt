package com.fauxx.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.ui.navigation.FauxxNavGraph
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Single-activity entry point for Fauxx. Hosts the Compose navigation graph.
 * Shows [com.fauxx.ui.screens.OnboardingScreen] on first launch only.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showOnboarding = runBlocking {
            val prefs = fauxxDataStore.data.first()
            !(prefs[PreferenceKeys.ONBOARDING_COMPLETED] ?: false)
        }

        setContent {
            FauxxTheme {
                FauxxNavGraph(showOnboarding = showOnboarding)
            }
        }
    }
}
