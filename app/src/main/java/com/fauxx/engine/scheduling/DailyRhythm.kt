package com.fauxx.engine.scheduling

import com.fauxx.data.device.DeviceDeriver
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * How fast the action budget accrues at a given moment, as a multiple of the intensity's rate.
 * [ActionBudget] integrates it, so the actions a day may spend follow its shape.
 */
fun interface AccrualPace {
    fun at(timeMs: Long): Double

    companion object {
        /** The rate itself, at every moment: the budget as before daily rhythm. */
        val FLAT = AccrualPace { 1.0 }
    }
}

/**
 * A persona's day and week (#322 item 5): when it starts, how it ramps up and winds down, how its
 * volume varies from day to day, and how weekends differ from weekdays.
 *
 * The history shared in #270 ramps up gradually from about 07:00 to 11:00, plateaus, tapers after
 * 22:00 with a short late tail on about one night in ten, and has weekend days at about twice a
 * weekday; daily totals vary widely. Fauxx switched on at the exact start of the allowed hours,
 * ran at one level until they ended, and did the same every day of the week.
 *
 * Two parts, both drawn per persona ([SessionHabits]) and per day through [DeviceDeriver.pick]'s
 * portable hash, so the same persona has the same days on every device:
 * - **Day factor:** a weekday or weekend lean ([SessionHabits.weekendRatio], which can go either
 *   way) times lognormal day-to-day variation ([SessionHabits.dailySpread]), each within
 *   [MIN_DAY_FACTOR]..[MAX_DAY_FACTOR], normalized so the mean over each calendar week (Monday to
 *   Sunday) is at most 1. The displayed rate holds as a weekly average (owner ruling on #322).
 * - **Day shape:** inside the allowed hours, nothing until the persona's wake time
 *   ([SessionHabits.wakeDelayMs] after the window opens), a linear ramp ([SessionHabits.rampMs]),
 *   a plateau, and a linear wind-down ([SessionHabits.windDownMs]) to the window's end, skipped on
 *   late nights ([SessionHabits.lateNightChance]). The plateau is set so the day's total is the day
 *   factor times the rate times the window's length, up to [MAX_PLATEAU] times the rate, under
 *   the engine's hourly ceiling.
 *
 * Outside the allowed hours nothing accrues. A window that is all day has no shape, only the day
 * factor of each local day.
 */
object DailyRhythm {

    /** Bounds on one day's volume, as a multiple of the rate. */
    const val MIN_DAY_FACTOR = 0.5
    const val MAX_DAY_FACTOR = 1.5

    /** Highest accrual on the plateau, as a multiple of the rate; the engine's hourly ceiling is 2. */
    const val MAX_PLATEAU = 1.8

    /** Wake, ramp and wind-down together take at most this share of the window. */
    private const val MAX_SHAPED_SHARE = 0.6

    private const val DOMAIN = "rhythm"

    /** The pace for [habits] in the window [allowedStart]..[allowedEnd] (see [AllowedHours]). */
    fun pace(habits: SessionHabits, allowedStart: Int, allowedEnd: Int): AccrualPace {
        if (habits.isFlat()) return AccrualPace.FLAT
        val days = HashMap<Long, DayPlan>()
        return AccrualPace { t ->
            if (AllowedHours.isAllDay(allowedStart, allowedEnd)) {
                dayFactor(habits, epochDayOf(t))
            } else {
                val start = AllowedHours.windowStartMs(t, allowedStart, allowedEnd) ?: return@AccrualPace 0.0
                val end = t + (AllowedHours.msUntilClose(t, allowedStart, allowedEnd) ?: return@AccrualPace 0.0)
                val plan = synchronized(days) {
                    days.getOrPut(start) { DayPlan.of(habits, start, end) }
                }
                plan.at(t)
            }
        }
    }

    /**
     * The volume of [epochDay] for [habits], as a multiple of the rate: the lean and variation of
     * its calendar week, normalized as the class KDoc says.
     */
    fun dayFactor(habits: SessionHabits, epochDay: Long): Double {
        val monday = epochDay - (LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1)
        val weekday = 7.0 / (5.0 + 2.0 * habits.weekendRatio)
        val raw = DoubleArray(7) { i ->
            val base = if (i >= 5) weekday * habits.weekendRatio else weekday
            base * lognormalNoise(habits.key, monday + i, habits.dailySpread)
        }
        repeat(3) {
            val mean = raw.average()
            for (i in raw.indices) raw[i] = (raw[i] / mean).coerceIn(MIN_DAY_FACTOR, MAX_DAY_FACTOR)
        }
        // Clamping can leave the mean slightly above 1. Take the excess off the days above the floor,
        // so the week never runs ahead and no day drops under it.
        repeat(5) {
            if (raw.average() <= 1.0) return@repeat
            val free = raw.indices.filter { raw[it] > MIN_DAY_FACTOR }
            val fixed = raw.indices.filterNot { it in free }.sumOf { raw[it] }
            val scale = (raw.size - fixed) / free.sumOf { raw[it] }
            for (i in free) raw[i] = (raw[i] * scale).coerceAtLeast(MIN_DAY_FACTOR)
        }
        return raw[(epochDay - monday).toInt()]
    }

    /** Whether [epochDay] is one of the persona's late nights, with no wind-down. */
    fun isLateNight(habits: SessionHabits, epochDay: Long): Boolean =
        DeviceDeriver.pick(habits.key, "$DOMAIN:late:$epochDay", 0, 1_000) < habits.lateNightChance * 1_000

    /** One window instance's shape. */
    private class DayPlan(
        private val start: Long,
        private val end: Long,
        private val wake: Long,
        private val ramp: Long,
        private val windDown: Long,
        private val plateau: Double,
    ) {
        fun at(t: Long): Double {
            val awake = start + wake
            return plateau * when {
                t < awake || t >= end -> 0.0
                t < awake + ramp -> (t - awake).toDouble() / ramp
                windDown > 0 && t >= end - windDown -> (end - t).toDouble() / windDown
                else -> 1.0
            }
        }

        companion object {
            fun of(habits: SessionHabits, start: Long, end: Long): DayPlan {
                val length = (end - start).coerceAtLeast(1L)
                val day = epochDayOf(start)
                val late = isLateNight(habits, day)
                var wake = habits.wakeDelayMs
                var ramp = habits.rampMs
                var windDown = if (late) 0L else habits.windDownMs
                val shaped = wake + ramp + windDown
                val room = (length * MAX_SHAPED_SHARE).toLong()
                if (shaped > room) {
                    val f = room.toDouble() / shaped
                    wake = (wake * f).toLong(); ramp = (ramp * f).toLong(); windDown = (windDown * f).toLong()
                }
                val effective = length - wake - ramp / 2.0 - windDown / 2.0
                val plateau = (dayFactor(habits, day) * length / effective).coerceAtMost(MAX_PLATEAU)
                return DayPlan(start, end, wake, ramp, windDown, plateau)
            }
        }
    }

    private fun epochDayOf(timeMs: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = timeMs }
        return LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)).toEpochDay()
    }

    /** Mean-1 lognormal noise for one day, from two hashed uniforms (Box-Muller). */
    private fun lognormalNoise(key: String, epochDay: Long, sigma: Double): Double {
        if (sigma <= 0.0) return 1.0
        val u1 = (DeviceDeriver.pick(key, "$DOMAIN:day:$epochDay:a", 0, 1_000_000) + 0.5) / 1_000_000
        val u2 = (DeviceDeriver.pick(key, "$DOMAIN:day:$epochDay:b", 0, 1_000_000) + 0.5) / 1_000_000
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        return exp(sigma * z - sigma * sigma / 2)
    }

    private fun SessionHabits.isFlat(): Boolean =
        wakeDelayMs == 0L && rampMs == 0L && windDownMs == 0L && dailySpread == 0.0 && weekendRatio == 1.0
}
