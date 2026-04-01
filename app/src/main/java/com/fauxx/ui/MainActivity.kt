package com.fauxx.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.logging.CrashDetector
import com.fauxx.ui.navigation.FauxxNavGraph
import com.fauxx.ui.screens.CrashReportDialog
import com.fauxx.ui.screens.LogExportSheet
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Single-activity entry point for Fauxx. Hosts the Compose navigation graph.
 * Shows [com.fauxx.ui.screens.OnboardingScreen] on first launch only.
 * Shows [CrashReportDialog] if the previous session crashed.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var crashDetector: CrashDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            FauxxTheme {
                var showOnboarding by remember { mutableStateOf<Boolean?>(null) }
                var showCrashDialog by remember {
                    mutableStateOf(crashDetector.hasCrashReport())
                }
                var showCrashExportSheet by remember { mutableStateOf(false) }
                var crashReportContent by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    showOnboarding = try {
                        val prefs = fauxxDataStore.data.first()
                        !(prefs[PreferenceKeys.ONBOARDING_COMPLETED] ?: false)
                    } catch (_: Exception) {
                        true // Show onboarding as fallback if DataStore read fails
                    }
                }

                if (showCrashDialog) {
                    CrashReportDialog(
                        onDismiss = {
                            crashDetector.dismissCrashReport()
                            showCrashDialog = false
                        },
                        onShare = {
                            val report = crashDetector.readCrashReport()
                            if (report != null) {
                                crashReportContent = report
                                showCrashExportSheet = true
                            }
                            crashDetector.dismissCrashReport()
                            showCrashDialog = false
                        }
                    )
                }

                if (showCrashExportSheet) {
                    LogExportSheet(
                        title = "Crash Report",
                        content = crashReportContent,
                        fileName = "fauxx_crash_report.txt",
                        onDismiss = { showCrashExportSheet = false }
                    )
                }

                val onboarding = showOnboarding
                if (onboarding != null) {
                    FauxxNavGraph(showOnboarding = onboarding)
                }
            }
        }
    }
}
