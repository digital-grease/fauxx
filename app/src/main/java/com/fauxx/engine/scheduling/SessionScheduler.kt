package com.fauxx.engine.scheduling

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.util.Clock
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Paces the engine in sessions (#322 item 1): runs of browsing separated by breaks, at the same
 * average rate as the intensity's, instead of a steady stream of independent actions.
 *
 * People browse in sessions: a few pages in quick succession, a pause to read, a few more, then
 * nothing for a while. The history shared in #270 has about 4 sessions a day, a median of about
 * 8 s between page loads inside a session (p90 about 2.5 min), and breaks of minutes to hours.
 * Before this, every action was scheduled on its own from an exponential gap around the rate, and
 * the hourly cap made every hour carry the same volume, a shape no person has.
 *
 * Three nested levels:
 * - **Burst:** a few actions on one topic (geometric, mean [SessionHabits.burstMean]), each after
 *   a short think pause ([SessionHabits.thinkMedianMs], lognormal) on top of the action's own
 *   dwell on its page. A burst holds at most one search, as its first action ([searchChance]):
 *   a person searches and then reads, rather than searching again every few seconds.
 * - **Session:** bursts separated by reading pauses. The pauses are sized so the session runs at
 *   [SessionHabits.sessionPace] times the rate, faster than the budget accrues, so the session
 *   ends when the budget saved before it runs out, or, in a share [SessionHabits.endEarly] of
 *   sessions, partway, leaving the rest for the next one. The next burst
 *   keeps the topic with chance [SessionHabits.stayOnTopic]; a change of topic waits at least
 *   [crossNicheFloorMs], as before.
 * - **Break:** a lognormal pause around [SessionHabits.breakMedianMs], longer in the persona's
 *   and the user's quiet hours ([RateModulator]). While it lasts the budget saves up, to at most
 *   one hour's worth; a break that runs on long after the budget is full ends soon after, so the
 *   rate does not drop because savings were thrown away.
 *
 * The [ActionBudget] keeps the average honest: an action needs one saved, so the count can never
 * run ahead of the rate, and a session can only spend what the break before it saved. Topics are
 * drawn from the targeting weights only when a burst or session starts, which leaves the long-run
 * mix exactly as the weights set it.
 *
 * The calmer tiers (Low, Medium) get clear sessions and breaks. At High and Max the actions' own
 * run time is the limit, so the pauses shrink to their minimum and breaks are rare: those tiers
 * stay close to continuous, the trade-off their detectability warning describes.
 *
 * Outside the allowed hours ([AllowedHours]) the wait runs to the window's start, and the engine
 * resigns rather than waiting.
 */
@Singleton
class SessionScheduler @Inject constructor(
    private val clock: Clock,
    private val random: Random,
    private val rateModulator: RateModulator,
    private val habitsSource: SessionHabitsSource,
) : ActionPacer {

    private enum class Wait { THINK, PAUSE, BREAK }

    private var budget = ActionBudget.holding(1.0, clock.currentTimeMillis())
    private var habits = SessionHabits.DEFAULT
    private var inSession = false
    private var burstLeft = 0
    private var burstPlanned = 0
    private var actionsInBurst = 0
    private var topic: CategoryPool? = null
    private var lastWait = Wait.BREAK

    /** The session ends at the end of a burst once the budget is below this. */
    private var stopBelow = 1.0

    /** Set when a run starts with nothing saved: the first session waits for a break to pass. */
    private var startBreakUntilMs = 0L

    /** Running estimate of how long an action takes, which the pause sizing subtracts. */
    private var execEstimateMs = INITIAL_EXEC_ESTIMATE_MS

    override fun startRun(
        nowMs: Long,
        actionsPerHour: Int,
        allowedStart: Int,
        allowedEnd: Int,
        successTimestamps: List<Long>,
    ) {
        budget = ActionBudget.replay(
            nowMs, actionsPerHour, AllowedHours.windowStartMs(nowMs, allowedStart, allowedEnd), successTimestamps,
        )
        habits = currentHabits()
        inSession = false
        burstLeft = 0
        topic = null
        lastWait = Wait.BREAK
        // With an action saved the first session starts at once, so turning the engine on shows
        // activity straight away. With none, the run opens with a break.
        startBreakUntilMs = if (budget.canSpend()) 0L else nowMs + breakMs(actionsPerHour, nowMs)
    }

    override fun msUntilAllowed(nowMs: Long, actionsPerHour: Int): Long {
        budget.accrue(nowMs, actionsPerHour)
        val untilBudget = budget.msUntil(1.0, actionsPerHour)
        return maxOf(untilBudget, startBreakUntilMs - nowMs, 0L)
    }

    override fun nextCategory(draw: () -> CategoryPool): CategoryPool {
        if (!inSession) {
            habits = currentHabits()
            inSession = true
            startBreakUntilMs = 0L
            startBurst()
            topic = null
            // Some sessions stop with part of the budget left. Deciding it per session, rather
            // than per burst, keeps that rare enough that savings seldom overflow in the break.
            stopBelow = if (random.nextDouble() < habits.endEarly) {
                budget.available * (EARLY_STOP_MIN + random.nextDouble() * (EARLY_STOP_MAX - EARLY_STOP_MIN))
            } else {
                1.0
            }
        }
        return topic ?: draw().also { topic = it }
    }

    override fun afterAction(
        actionsPerHour: Int,
        executed: CategoryPool,
        success: Boolean,
        execMs: Long,
        allowedStart: Int,
        allowedEnd: Int,
    ): Long {
        val nowMs = clock.currentTimeMillis()
        budget.accrue(nowMs, actionsPerHour)
        if (success) {
            budget.spend()
            execEstimateMs += EXEC_ESTIMATE_WEIGHT * (execMs.coerceIn(0L, MAX_EXEC_SAMPLE_MS) - execEstimateMs)
        }

        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (!AllowedHours.isWithin(now.get(Calendar.HOUR_OF_DAY), allowedStart, allowedEnd)) {
            // Uses the SAME predicate as the engine's constraint gate, so the two can never
            // disagree about whether "now" is active (issue #124).
            endSession()
            lastWait = Wait.BREAK
            return msUntilHour(now, allowedStart)
        }

        burstLeft--
        actionsInBurst++
        // A module can log a different topic than planned (E8: AppSignal's persona-interest swap);
        // the visible stream changed topic, so the burst ends there.
        val redirected = topic != null && executed != topic
        return when {
            !budget.canSpend() -> endSessionWithBreak(actionsPerHour, nowMs)
            burstLeft > 0 && !redirected -> {
                lastWait = Wait.THINK
                thinkMs(actionsPerHour)
            }
            budget.available < stopBelow -> endSessionWithBreak(actionsPerHour, nowMs)
            else -> {
                startBurst()
                val stay = !redirected && random.nextDouble() < habits.stayOnTopic
                if (!stay) topic = null
                lastWait = Wait.PAUSE
                val pause = pauseMs(actionsPerHour)
                if (stay) pause else aboveCrossNicheFloor(pause, actionsPerHour)
            }
        }
    }

    /**
     * The first action of a burst gets the burst's share of searches, as if each of its planned
     * actions had an even chance of being one; the rest of the burst gets none. Over many bursts
     * that keeps searches close to their even share, minus the long bursts, which hold one.
     */
    override fun searchChance(moduleCount: Int): Double =
        if (actionsInBurst == 0) minOf(1.0, burstPlanned.toDouble() / moduleCount.coerceAtLeast(1)) else 0.0

    override fun redrawDelayMs(actionsPerHour: Int): Long = when (lastWait) {
        Wait.THINK -> thinkMs(actionsPerHour)
        Wait.PAUSE -> pauseMs(actionsPerHour)
        Wait.BREAK -> breakMs(actionsPerHour, clock.currentTimeMillis())
    }

    private fun currentHabits(): SessionHabits =
        runCatching { habitsSource.habits() }.getOrDefault(SessionHabits.DEFAULT)

    private fun endSession() {
        inSession = false
        burstLeft = 0
        actionsInBurst = 0
        topic = null
    }

    private fun startBurst() {
        burstPlanned = burstSize()
        burstLeft = burstPlanned
        actionsInBurst = 0
    }

    private fun endSessionWithBreak(actionsPerHour: Int, nowMs: Long): Long {
        endSession()
        lastWait = Wait.BREAK
        return aboveCrossNicheFloor(breakMs(actionsPerHour, nowMs), actionsPerHour)
    }

    /** Actions in a burst: 1 plus a geometric count, mean [SessionHabits.burstMean]. */
    private fun burstSize(): Int {
        val continueChance = (1.0 - 1.0 / habits.burstMean.coerceAtLeast(1.0)).coerceIn(0.0, MAX_BURST_CONTINUE)
        var size = 1
        while (size < MAX_BURST && random.nextDouble() < continueChance) size++
        return size
    }

    /**
     * The pause before the next action in the same burst, at the persona's own speed whatever the
     * tier: shrinking it at High and Max left many actions under a second apart in the log.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun thinkMs(actionsPerHour: Int): Long =
        lognormal(habits.thinkMedianMs.toDouble(), THINK_SIGMA).toLong().coerceIn(MIN_THINK_MS, MAX_THINK_MS)

    /**
     * The reading pause between bursts, sized so a session runs at [SessionHabits.sessionPace]
     * times the rate: a burst's share of the session's time, less what its actions and the think
     * pauses between them take. At High and Max that leaves nothing, so the pause is short.
     */
    private fun pauseMs(actionsPerHour: Int): Long {
        val msPerAction = HOUR_MS / (habits.sessionPace * actionsPerHour.coerceAtLeast(1))
        val thinkMean = habits.thinkMedianMs * exp(THINK_SIGMA * THINK_SIGMA / 2)
        val mean = (habits.burstMean * (msPerAction - execEstimateMs) - (habits.burstMean - 1) * thinkMean)
            .coerceAtLeast(MIN_PAUSE_MEAN_MS)
        return lognormal(mean / exp(PAUSE_SIGMA * PAUSE_SIGMA / 2), PAUSE_SIGMA).toLong()
            .coerceIn(MIN_THINK_MS, MAX_PAUSE_MS)
    }

    /**
     * A break between sessions: lognormal around the persona's median, longer in quiet hours.
     * Once the budget would be full, saving stops, so the break ends an exponential overrun after
     * that instead of running on; and it always lasts until at least one action is saved.
     */
    private fun breakMs(actionsPerHour: Int, nowMs: Long): Long {
        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        val modulation = rateModulator.multiplier(hour)
            .coerceIn(RateModulator.MIN_MULTIPLIER, RateModulator.MAX_MULTIPLIER).toDouble()
        val drawn = lognormal(habits.breakMedianMs / modulation, BREAK_SIGMA)
        val untilFull = budget.msUntilFull(actionsPerHour) + exponential(BREAK_OVERRUN_MEAN_MS)
        val untilOne = budget.msUntil(1.0, actionsPerHour)
        return maxOf(minOf(drawn, untilFull.toDouble()).toLong(), untilOne)
    }

    /**
     * A change of topic waits at least the tier's floor. Heuristic bot detection flags quick jumps
     * between unrelated niches (Finance to Legal in 4 s), while people move between pages on one
     * subject quickly. A short draw is lifted above the floor by an exponential amount rather than
     * clamped to it, so there is no spike of gaps at exactly the floor (#382).
     */
    private fun aboveCrossNicheFloor(delayMs: Long, actionsPerHour: Int): Long {
        val floor = crossNicheFloorMs(actionsPerHour)
        return if (delayMs >= floor) delayMs else floor + exponential(floor / 2.0)
    }

    private fun lognormal(median: Double, sigma: Double): Double = median * exp(sigma * standardNormal())

    /** Box-Muller. */
    private fun standardNormal(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    private fun exponential(mean: Double): Long =
        (-ln(1.0 - random.nextDouble()) * mean).toLong()

    private fun msUntilHour(now: Calendar, targetHour: Int): Long {
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, targetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (target.before(now)) target.add(Calendar.DAY_OF_MONTH, 1)
        return maxOf(target.timeInMillis - now.timeInMillis, 60_000L)
    }

    companion object {
        /** Default quiet hours: 11pm to 7am. */
        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7

        private const val HOUR_MS = 3_600_000.0

        private const val THINK_SIGMA = 0.8
        private const val PAUSE_SIGMA = 0.9
        private const val BREAK_SIGMA = 0.7
        private const val BREAK_OVERRUN_MEAN_MS = 8.0 * 60 * 1000

        private const val MIN_THINK_MS = 500L
        private const val MAX_THINK_MS = 2L * 60 * 1000
        private const val MIN_PAUSE_MEAN_MS = 2_000.0
        private const val MAX_PAUSE_MS = 25L * 60 * 1000

        /** An early stop leaves this share of the session's starting budget, drawn evenly. */
        private const val EARLY_STOP_MIN = 0.3
        private const val EARLY_STOP_MAX = 0.7

        private const val MAX_BURST = 20
        private const val MAX_BURST_CONTINUE = 0.9

        /** Until the first actions are timed: roughly a page load plus its dwell. */
        private const val INITIAL_EXEC_ESTIMATE_MS = 10_000.0
        private const val EXEC_ESTIMATE_WEIGHT = 0.1
        private const val MAX_EXEC_SAMPLE_MS = 120_000L

        /** Bounds for the cross-niche floor, see [crossNicheFloorMs]. */
        private const val CROSS_NICHE_FLOOR_MIN_MS = 5_000L
        private const val CROSS_NICHE_FLOOR_MAX_MS = 30_000L

        /**
         * Minimum gap before a change of topic, from the rate so the aggressive tiers can still
         * reach theirs: half the mean gap at the rate, within 5-30 s. Low and Medium get 30 s,
         * High 9 s, Max 5 s.
         */
        internal fun crossNicheFloorMs(actionsPerHour: Int): Long {
            if (actionsPerHour <= 0) return CROSS_NICHE_FLOOR_MAX_MS
            val halfMean = (3_600_000L / actionsPerHour) / 2
            return halfMean.coerceIn(CROSS_NICHE_FLOOR_MIN_MS, CROSS_NICHE_FLOOR_MAX_MS)
        }
    }
}
