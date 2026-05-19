package com.fauxx.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.fauxx.R
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.logging.BootGuard
import com.fauxx.logging.CrashDetector
import com.fauxx.service.PhantomForegroundService
import com.fauxx.ui.navigation.FauxxNavGraph
import com.fauxx.ui.screens.CrashReportDialog
import com.fauxx.ui.screens.LogExportSheet
import com.fauxx.ui.theme.FauxxTheme
import com.fauxx.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
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

    @Inject
    lateinit var bootGuard: BootGuard

    /**
     * Apply the per-app language stored by `AppCompatDelegate.setApplicationLocales()`
     * to this Activity's base context before resources are inflated.
     *
     * On API 33+ Android propagates the locale automatically; this override is harmless
     * because the system has already updated the configuration. On API 26–32 the AppCompat
     * backport stores the choice but only auto-applies it when the host activity extends
     * `AppCompatActivity`. fauxx is pure Compose on `ComponentActivity`, so we do the
     * application manually here. `AppCompatDelegate.getApplicationLocales()` is a sync
     * read backed by AppCompat's own SharedPreferences (populated at process start by
     * `AppLocalesMetadataHolderService` with `autoStoreLocales=true`).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyAppLocale(newBase))
    }

    private fun applyAppLocale(base: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val first = locales[0] ?: return base
        Locale.setDefault(first)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(first))
        return base.createConfigurationContext(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val inSafeMode = bootGuard.isInSafeMode()
        if (inSafeMode) {
            // Two boots in a row failed to reach the success callback — the most likely
            // cause is that PhantomWebViewPool's WebView constructor is hanging Main when
            // a Layer 2 scrape kicks off. Force-disable Layer 2 + the engine before
            // anything tries to start them, then let startup proceed normally.
            Timber.w("BootGuard: safe mode active — disabling Layer 2 and engine")
            lifecycleScope.launch {
                runCatching {
                    fauxxDataStore.edit { prefs ->
                        prefs[PreferenceKeys.LAYER2_ENABLED] = false
                        prefs[PreferenceKeys.ENABLED] = false
                    }
                }.onFailure { Timber.e(it, "Failed to write safe-mode prefs") }
            }
        }

        if (!inSafeMode) {
            reconcileEngineState(intent)
        }

        // Surface a one-time toast to the user the first time we exit safe mode so they
        // know why Layer 2 (and the engine) were turned off.
        if (bootGuard.consumePendingRecoveryNotice()) {
            Toast.makeText(
                this,
                R.string.boot_guard_safe_mode_notice,
                Toast.LENGTH_LONG
            ).show()
        }

        // Reset the boot counter after the main thread has been responsive for
        // BOOT_SUCCESS_DELAY_MS. If main is hung before then, the callback never runs
        // and the counter survives to the next boot.
        Handler(Looper.getMainLooper()).postDelayed(
            { runCatching { bootGuard.recordBootSuccess() } },
            BootGuard.BOOT_SUCCESS_DELAY_MS
        )

        val themeFlow = fauxxDataStore.data
            .map { prefs ->
                runCatching {
                    ThemeMode.valueOf(prefs[PreferenceKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
                }.getOrDefault(ThemeMode.SYSTEM)
            }
            .distinctUntilChanged()

        setContent {
            val themeMode by themeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            FauxxTheme(mode = themeMode) {
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
