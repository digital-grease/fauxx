package com.fauxx.shape

import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.support.MainDispatcherRule
import java.io.File
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The stream-shape report (#322 step 0): runs [EngineShapeHarness] for two simulated weeks per
 * intensity tier and writes `build/reports/stream-shape/<tier>.md`, the same metrics #322 compares
 * against a real browsing history.
 *
 * The report is for reading, before and after a change to the stream's shape. The assertions only
 * hold the invariants every shape must keep:
 * - Nothing starts outside the allowed hours. The engine checks the window before each action, so
 *   an action already running when the window closes still finishes; its requests may run up to
 *   [MAX_IN_FLIGHT_OVERRUN_MS] past the close, and the report counts them.
 * - No pause the harness did not expect.
 * - An actual rate at or under the tier's displayed rate, and no lower than [MIN_RATE_FRACTION] of
 *   it (the honesty contract, #76). The floors sit just under the rates measured when the report
 *   was added, so a change that loses throughput trips them; EXTREME's is lowest because its rate
 *   is documented as "up to" and action run time caps it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamShapeReportTest {

    private val main = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(main)

    @Test
    fun `low intensity report`() = report(IntensityLevel.LOW)

    @Test
    fun `medium intensity report`() = report(IntensityLevel.MEDIUM)

    @Test
    fun `high intensity report`() = report(IntensityLevel.HIGH)

    @Test
    fun `extreme intensity report`() = report(IntensityLevel.EXTREME)

    private fun report(tier: IntensityLevel) = runTest(main, timeout = 5.minutes) {
        val profile = PoisonProfile(
            enabled = true,
            intensity = tier,
            // The harness's network mock reads as mobile data, so the mobile tier is the one that governs.
            mobileIntensity = tier,
            batteryThresholdBattery = 0,
            batteryThresholdCharging = 0,
        )
        val harness = EngineShapeHarness(this, RuntimeEnvironment.getApplication(), profile, SEED, startEpochMs())
        val recording = harness.run(DAYS)
        val shape = StreamShape.measure(recording)

        val report = ShapeReport.markdown(
            title = "$tier (${tier.actionsPerHour} actions/hour), default modules",
            shape = shape,
            notes = listOf(
                "Allowed hours ${profile.allowedHoursStart}:00-${profile.allowedHoursEnd}:00, Layer 3 persona on, English corpus.",
                "Every page load succeeds and every results page has clickable corpus results, " +
                    "so clicks are an upper bound.",
            ),
        )
        File(REPORT_DIR).apply { mkdirs() }.resolve("${tier.name.lowercase()}.md").writeText(report)
        println(report)

        val startedOutside = recording.events.filter { event ->
            val sinceClose = StreamShape.msSinceClose(event.atMs, profile.allowedHoursStart, profile.allowedHoursEnd)
            sinceClose != null && sinceClose > MAX_IN_FLIGHT_OVERRUN_MS
        }
        fun check(message: String, ok: Boolean) =
            assertTrue("$message\nLast engine log lines:\n${harness.logTail()}", ok)
        check(
            "${startedOutside.size} requests came more than ${MAX_IN_FLIGHT_OVERRUN_MS / 1000} s after the window closed",
            startedOutside.isEmpty(),
        )
        check("unexpected pauses: ${harness.unexpectedResigns}", harness.unexpectedResigns.isEmpty())
        check(
            "actual rate ${shape.actionsPerActiveHour}/h is above the displayed ${tier.actionsPerHour}/h",
            shape.actionsPerActiveHour <= tier.actionsPerHour,
        )
        check(
            "actual rate ${shape.actionsPerActiveHour}/h is far below the displayed ${tier.actionsPerHour}/h",
            shape.actionsPerActiveHour >= tier.actionsPerHour * MIN_RATE_FRACTION.getValue(tier),
        )
    }

    private companion object {
        const val SEED = 20261028L
        const val DAYS = 14
        const val REPORT_DIR = "build/reports/stream-shape"

        /**
         * Lowest acceptable fraction of each tier's displayed rate; see the class KDoc. Measured when
         * added: LOW 0.87, MEDIUM 0.89, HIGH 0.82, EXTREME 0.62.
         */
        val MIN_RATE_FRACTION = mapOf(
            IntensityLevel.LOW to 0.80,
            IntensityLevel.MEDIUM to 0.80,
            IntensityLevel.HIGH to 0.75,
            IntensityLevel.EXTREME to 0.55,
        )

        /**
         * How far past the window's close an action already running may go: the search session's
         * 60 s budget plus the step it was in. Anything later started outside the window.
         */
        const val MAX_IN_FLIGHT_OVERRUN_MS = 2L * 60 * 1000

        /** Local midnight on Monday 2026-01-05: two weeks with no daylight-saving change in either hemisphere. */
        fun startEpochMs(): Long = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JANUARY, 5, 0, 0, 0)
        }.timeInMillis
    }
}
