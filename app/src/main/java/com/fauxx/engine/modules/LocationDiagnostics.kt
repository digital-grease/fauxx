package com.fauxx.engine.modules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surface of the most-recent location-spoof startup outcome, observable by the UI so
 * the ModulesScreen can show the user *why* the toggle is silently doing nothing.
 *
 * Implemented by `LocationSpoofModule` in the full flavor (where addTestProvider can
 * fail in user-recoverable ways) and by [NoOpLocationDiagnostics] in the play flavor
 * (where `LocationSignalModule` is HTTP-only and has no equivalent failure mode).
 */
interface LocationDiagnostics {
    val lastStartFailure: StateFlow<StartFailure>

    /**
     * Whether Fauxx is currently designated as the system mock-location provider
     * (Developer Options → "Select mock location app"). Used by the UI to decide
     * whether to surface the setup-hint dialog when the user toggles the module on
     * — there's no point telling them to do the setup if it's already done.
     *
     * Always `false` on the play flavor, where there is no mock-provider integration.
     */
    fun isMockLocationAppOpAllowed(): Boolean

    /**
     * Re-invokes the underlying location module's `start()` outside the engine's
     * startup loop. Lets the user get instant green-banner / red-banner feedback
     * the moment they toggle the module on, instead of having to wait for the next
     * engine restart for `lastStartFailure` to update.
     *
     * No-op on the play flavor.
     */
    suspend fun requestStart()

    enum class StartFailure {
        /** start() has not run yet for this module-singleton lifetime. */
        NEVER_STARTED,

        /** Module ran and the mock provider was successfully registered. */
        OK,

        /**
         * The user has not selected this app under Developer Options →
         * "Select mock location app". Detected proactively via
         * `AppOpsManager.OPSTR_MOCK_LOCATION` before attempting addTestProvider.
         */
        NOT_MOCK_APP,

        /**
         * The AppOp check said we're authorized, but addTestProvider still threw
         * SecurityException — observed Samsung One UI behavior where the AppOps
         * state desyncs from the Developer Options selection. Restarting the app
         * usually resolves this.
         */
        SECURITY_EXCEPTION,

        /**
         * addTestProvider threw something other than SecurityException
         * (e.g., IllegalArgumentException). See logs for details.
         */
        RUNTIME_EXCEPTION
    }
}

/** Play-flavor stub: always reports OK because LocationSignalModule cannot fail in this way. */
@Singleton
class NoOpLocationDiagnostics @Inject constructor() : LocationDiagnostics {
    private val _state = MutableStateFlow(LocationDiagnostics.StartFailure.OK)
    override val lastStartFailure: StateFlow<LocationDiagnostics.StartFailure> = _state.asStateFlow()
    override fun isMockLocationAppOpAllowed(): Boolean = false
    override suspend fun requestStart() { /* no-op — play flavor has no mock-provider lifecycle */ }
}
