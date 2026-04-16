package com.fauxx.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.logging.CrashDetector
import com.fauxx.service.PhantomForegroundService
import com.fauxx.ui.navigation.FauxxNavGraph
import com.fauxx.ui.screens.CrashReportDialog
import com.fauxx.ui.screens.LogExportSheet
import com.fauxx.ui.theme.FauxxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import timber.log.Timber
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

        handleResumeEngineIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResumeEngineIntent(intent)
    }

    /**
     * If launched (or re-launched) via the BootReceiver's "tap to resume" notification,
     * start [PhantomForegroundService] from this user-interaction context. Starting the
     * FGS from an activity lifecycle that originated from a notification tap is always
     * an allowed FGS-start context on Android 14+, even for dataSync-type services.
     *
     * The extra is consumed (removed) after handling so config changes don't re-trigger.
     */
    private fun handleResumeEngineIntent(intent: Intent?) {
        if (intent == null) return
        if (!intent.getBooleanExtra(EXTRA_RESUME_ENGINE, false)) return
        intent.removeExtra(EXTRA_RESUME_ENGINE)
        Timber.i("Resume-from-boot intent received; starting PhantomForegroundService")
        try {
            ContextCompat.startForegroundService(
                this,
                PhantomForegroundService.startIntent(this)
            )
        } catch (e: IllegalStateException) {
            Timber.e(e, "Failed to start PhantomForegroundService on resume")
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to start PhantomForegroundService on resume (SecurityException)")
        }
    }

    companion object {
        /** Boolean extra: when true, [MainActivity] starts the engine FGS on launch. */
        const val EXTRA_RESUME_ENGINE = "com.fauxx.extra.RESUME_ENGINE"
    }
}
