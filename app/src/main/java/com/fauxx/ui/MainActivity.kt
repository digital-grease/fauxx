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
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
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

        reconcileEngineState(intent)

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
        reconcileEngineState(intent)
    }

    /**
     * Reconcile persisted engine intent vs runtime service state on every launch.
     *
     * If the user had the engine enabled (DataStore `ENABLED=true`) but the FGS is not
     * running — e.g., after a reboot where BootReceiver couldn't post its tap-to-resume
     * notification (POST_NOTIFICATIONS denied) — start it now. Activity launch from the
     * launcher or from a notification tap is always an allowed FGS-start context on
     * Android 14+, even for dataSync-type services.
     *
     * [PoisonEngine.start] and [PhantomForegroundService.onStartCommand] are idempotent,
     * so re-dispatching `ACTION_START` when the service is already running is a no-op.
     *
     * The [EXTRA_RESUME_ENGINE] extra (set by BootReceiver's resume notification) is
     * consumed here for cleanliness; the reconcile itself is driven by the persisted
     * `ENABLED` flag, not the extra, so launcher-open works identically.
     */
    private fun reconcileEngineState(intent: Intent?) {
        intent?.removeExtra(EXTRA_RESUME_ENGINE)
        lifecycleScope.launch {
            val enabled = try {
                fauxxDataStore.data.first()[PreferenceKeys.ENABLED] ?: false
            } catch (e: Exception) {
                Timber.w(e, "Failed to read ENABLED flag during reconcile")
                return@launch
            }
            if (!enabled) return@launch
            Timber.i("Reconcile: ENABLED=true, starting PhantomForegroundService")
            try {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    PhantomForegroundService.startIntent(this@MainActivity)
                )
            } catch (e: IllegalStateException) {
                Timber.e(e, "Failed to start PhantomForegroundService on reconcile")
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to start PhantomForegroundService on reconcile (SecurityException)")
            }
        }
    }

    companion object {
        /**
         * Boolean extra set by [com.fauxx.service.BootReceiver]'s resume notification.
         * Retained as a marker of the notification-tap entry path; the actual FGS start
         * is now driven by the persisted `ENABLED` flag in [reconcileEngineState], so
         * launcher-open and notification-tap share one start path.
         */
        const val EXTRA_RESUME_ENGINE = "com.fauxx.extra.RESUME_ENGINE"
    }
}
