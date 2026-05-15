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
}
