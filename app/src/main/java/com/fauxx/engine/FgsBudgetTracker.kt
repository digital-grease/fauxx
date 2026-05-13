package com.fauxx.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import com.fauxx.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cumulative-budget tracker for the Android 14+ `dataSync` foreground-service
 * runtime cap (6h per rolling 24h while backgrounded).
 *
 * The in-process [PoisonEngine] timer (`engineStartElapsedMs`) only knows about the
 * *current* engine session. If the user runs the engine for several hours, stops it,
 * then restarts it within the same 24h window, our in-process limit resets but the
 * OS's does not — the next session can still exhaust the OS budget partway through
 * and crash with [android.app.RemoteServiceException.ForegroundServiceDidNotStopInTimeException].
 *
 * This tracker persists "FGS time used so far in the current 24h window" so a fresh
 * engine session can compute its *effective* remaining budget on start. When the
 * persisted window is older than 24h, it resets — matching the OS's rolling-window
 * reset.
 *
 * Window model: fixed start time, not rolling. This is more conservative than the
 * OS's true rolling window (the user may be denied a session even when the OS would
 * still allow some runtime) but never less conservative, so it cannot cause a crash.
 * Trade simplicity over UX precision; revisit if users hit the "engine refuses to
 * start" path more than expected.
 */
@Singleton
class FgsBudgetTracker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock
) {
    companion object {
        /** 24h rolling window — matches Android's enforcement window. */
        const val WINDOW_MS = 24L * 60 * 60 * 1000

        /** 5h budget — 1h margin under Android's 6h cap, leaves room for in-flight resign. */
        const val BUDGET_MS = 5L * 60 * 60 * 1000
    }

    private data class State(val windowStartMs: Long, val usedMs: Long)

    /** Latest cached snapshot of (windowStartMs, usedMs). Updated reactively from DataStore. */
    private val cached = AtomicReference(State(0L, 0L))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                cached.set(
                    State(
                        windowStartMs = prefs[PreferenceKeys.FGS_BUDGET_WINDOW_START] ?: 0L,
                        usedMs = prefs[PreferenceKeys.FGS_BUDGET_USED_MS] ?: 0L
                    )
                )
            }
        }
    }

    /**
     * Returns the remaining FGS runtime budget (ms) available in the current 24h window.
     * Returns [BUDGET_MS] when the persisted window has expired or is unset.
     * Non-suspending — reads from a cache populated reactively from DataStore.
     */
    fun remainingBudgetMs(): Long {
        val state = cached.get()
        val nowMs = clock.currentTimeMillis()
        if (state.windowStartMs == 0L || nowMs - state.windowStartMs >= WINDOW_MS) {
            return BUDGET_MS
        }
        return (BUDGET_MS - state.usedMs).coerceAtLeast(0)
    }

    /**
     * Returns the wall-clock epoch ms at which the current window expires (and budget
     * resets to [BUDGET_MS]). When no window has been started yet, returns "now" so the
     * caller's `at-time` resume can fire as soon as possible.
     */
    fun nextWindowResetMs(): Long {
        val state = cached.get()
        if (state.windowStartMs == 0L) return clock.currentTimeMillis()
        return state.windowStartMs + WINDOW_MS
    }

    /**
     * Add a completed session's duration to the current window. Starts a fresh window
     * if none exists or the existing one has expired.
     */
    suspend fun recordSession(durationMs: Long) {
        if (durationMs <= 0) return
        val nowMs = clock.currentTimeMillis()
        dataStore.edit { prefs ->
            val windowStartMs = prefs[PreferenceKeys.FGS_BUDGET_WINDOW_START] ?: 0L
            val used = prefs[PreferenceKeys.FGS_BUDGET_USED_MS] ?: 0L
            if (windowStartMs == 0L || nowMs - windowStartMs >= WINDOW_MS) {
                // New window. Pin its start to "session-start" so it expires when the
                // session itself ages out, rather than "now" which would understate the
                // session's age within the window.
                prefs[PreferenceKeys.FGS_BUDGET_WINDOW_START] = (nowMs - durationMs).coerceAtLeast(0)
                prefs[PreferenceKeys.FGS_BUDGET_USED_MS] = durationMs.coerceAtMost(WINDOW_MS)
            } else {
                prefs[PreferenceKeys.FGS_BUDGET_USED_MS] = used + durationMs
            }
        }
    }
}
