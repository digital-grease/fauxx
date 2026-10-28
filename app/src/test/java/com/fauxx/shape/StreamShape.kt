package com.fauxx.shape

import com.fauxx.data.model.ActionType
import com.fauxx.engine.scheduling.AllowedHours
import java.net.URI
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * What a tracker or a network observer sees of one request in the noise stream (#322 step 0).
 */
internal enum class LoadKind {
    /** A search results page. */
    SERP,

    /** Any other top-level page load: a crawl visit, an ad page, a clicked search result. */
    PAGE,

    /** A standalone lookup from the DNS noise module. */
    DNS,
}

/**
 * One observable request, recorded by [EngineShapeHarness].
 *
 * @property host The host the request went to.
 * @property url The full URL of a page load; null for a [LoadKind.DNS] lookup.
 * @property engine The search engine id of a [LoadKind.SERP] load; null otherwise.
 * @property fromSearch True for a page reached by clicking a search result.
 */
internal data class StreamEvent(
    val atMs: Long,
    val kind: LoadKind,
    val host: String,
    val url: String? = null,
    val engine: String? = null,
    val fromSearch: Boolean = false,
) {
    /** The host without a leading "www.", which is what "the same site" means in the #270 numbers. */
    val site: String get() = host.removePrefix("www.")

    val isPageLoad: Boolean get() = kind != LoadKind.DNS
}

/** One action the engine logged, at the time it was logged. */
internal data class ActionRecord(val atMs: Long, val type: ActionType, val success: Boolean)

/**
 * A finished simulation: every observable request and logged action over [days] local days
 * starting at [startEpochMs] (local midnight), with the allowed-hours window that applied.
 */
internal data class StreamRecording(
    val events: List<StreamEvent>,
    val actions: List<ActionRecord>,
    val startEpochMs: Long,
    val days: Int,
    val allowedStart: Int,
    val allowedEnd: Int,
)

/**
 * The stream's shape in the terms of the #322 comparison table, measured from a [StreamRecording].
 *
 * Page loads (search pages and other pages) are the unit for everything a person's browsing
 * history would show; standalone DNS lookups only feed [standaloneDnsShare]. Shares are 0..1,
 * and a value that cannot be computed from the recording (no searches, no weekend day) is NaN.
 *
 * Sessions are runs of page loads separated by an idle gap longer than [SESSION_GAP_MS], the
 * usual web-analytics convention; #270 does not say which threshold it used.
 */
internal data class StreamShape(
    val days: Int,
    /** Successful logged actions per hour of the allowed window: the displayed-rate contract (#76). */
    val actionsPerActiveHour: Double,
    val loadsPerDayMedian: Double,
    val loadsPerDayP90: Double,
    /** Coefficient of variation of the daily page-load totals. */
    val dailyCv: Double,
    val gapsUnder10s: Double,
    val gapMedianS: Double,
    val gapP90S: Double,
    /** Coefficient of variation of the gaps inside sessions; an exponential stream has 1.0. */
    val inSessionGapCv: Double,
    /** Share of in-session gaps in the single most common one-second bucket (#382's "every minute"). */
    val modalGapShare: Double,
    val modalGapS: Double,
    /** The same for the gaps between successful logged actions, the cadence the log shows (#382). */
    val modalActionGapShare: Double,
    val modalActionGapS: Double,
    val sessionsPerDayMedian: Double,
    val sessionSizeMedian: Double,
    val sessionSizeP90: Double,
    val sitesPerSessionMedian: Double,
    /** Share of consecutive in-session page loads that stay on the same site. */
    val sameSiteNavShare: Double,
    val top10Share: Double,
    val top50Share: Double,
    /** [top10Share] over pages other than search results, which is where habitual sites show. */
    val top10NonSearchShare: Double,
    /** Share of a day's page loads to sites seen in the previous 14 days (from day 2 on). */
    val revisitShare: Double,
    /** [revisitShare] over pages other than search results. */
    val revisitNonSearchShare: Double,
    val homePageShare: Double,
    val queryStringShare: Double,
    val pathDepthMedian: Double,
    val topEngineShare: Double,
    /** Share of search pages that no clicked result follows before the next search. */
    val searchNoClickShare: Double,
    /** Share of search pages followed by another search within two minutes. */
    val reSearchShare: Double,
    val firstLoadMinutesAfterStartMedian: Double,
    /** Share of active days whose first page load comes within a minute of the window opening. */
    val daysStartingOnTheHour: Double,
    val weekendToWeekdayRatio: Double,
    /** Share of the DNS module's lookups with no page load within [DNS_BURST_WINDOW_MS]. */
    val standaloneDnsShare: Double,
    /** Requests (page loads and lookups) made while the allowed-hours window was closed. */
    val afterCloseCount: Int,
    /** How long after the window closed the latest of those came; 0 when there were none. */
    val afterCloseMaxS: Double,
) {
    companion object {
        const val SESSION_GAP_MS = 30L * 60 * 1000
        const val DNS_BURST_WINDOW_MS = 2_000L
        private const val RE_SEARCH_WINDOW_MS = 2L * 60 * 1000
        private const val REVISIT_LOOKBACK_DAYS = 14
        private const val ON_THE_HOUR_MS = 60_000L

        fun measure(recording: StreamRecording): StreamShape {
            val dayStarts = dayStarts(recording.startEpochMs, recording.days)
            val loads = recording.events.filter { it.isPageLoad }.sortedBy { it.atMs }
            val serps = loads.filter { it.kind == LoadKind.SERP }

            val activeHoursPerDay = (0 until 24).count {
                AllowedHours.isWithin(it, recording.allowedStart, recording.allowedEnd)
            }
            val successes = recording.actions.count { it.success }
            val actionsPerActiveHour = successes.toDouble() / (recording.days * activeHoursPerDay)

            val loadsByDay = IntArray(recording.days)
            for (load in loads) dayIndex(dayStarts, load.atMs)?.let { loadsByDay[it]++ }
            val daily = loadsByDay.map { it.toDouble() }

            val gaps = loads.zipWithNext { a, b -> (b.atMs - a.atMs).toDouble() }
            val inSessionGaps = gaps.filter { it <= SESSION_GAP_MS }
            val modal = modalBucket(inSessionGaps)
            val actionGaps = recording.actions.filter { it.success }.map { it.atMs }.sorted()
                .zipWithNext { a, b -> (b - a).toDouble() }
                .filter { it <= SESSION_GAP_MS }
            val modalAction = modalBucket(actionGaps)

            val sessions = sessions(loads)
            val sessionsByDay = IntArray(recording.days)
            for (session in sessions) dayIndex(dayStarts, session.first().atMs)?.let { sessionsByDay[it]++ }
            val inSessionPairs = sessions.flatMap { it.zipWithNext() }

            val nonSearch = loads.filter { it.kind == LoadKind.PAGE }

            val urls = loads.mapNotNull { load -> load.url?.let { runCatching { URI(it) }.getOrNull() } }

            val engineCounts = serps.groupingBy { it.engine }.eachCount()

            val afterClose = recording.events.mapNotNull { msSinceClose(it.atMs, recording.allowedStart, recording.allowedEnd) }

            return StreamShape(
                days = recording.days,
                actionsPerActiveHour = actionsPerActiveHour,
                loadsPerDayMedian = quantile(daily, 0.5),
                loadsPerDayP90 = quantile(daily, 0.9),
                dailyCv = cv(daily),
                gapsUnder10s = share(gaps) { it < 10_000 },
                gapMedianS = quantile(gaps, 0.5) / 1000,
                gapP90S = quantile(gaps, 0.9) / 1000,
                inSessionGapCv = cv(inSessionGaps),
                modalGapShare = modal?.let { it.value.toDouble() / inSessionGaps.size } ?: Double.NaN,
                modalGapS = modal?.key?.toDouble() ?: Double.NaN,
                modalActionGapShare = modalAction?.let { it.value.toDouble() / actionGaps.size } ?: Double.NaN,
                modalActionGapS = modalAction?.key?.toDouble() ?: Double.NaN,
                sessionsPerDayMedian = quantile(sessionsByDay.map { it.toDouble() }, 0.5),
                sessionSizeMedian = quantile(sessions.map { it.size.toDouble() }, 0.5),
                sessionSizeP90 = quantile(sessions.map { it.size.toDouble() }, 0.9),
                sitesPerSessionMedian = quantile(sessions.map { s -> s.map { it.site }.toSet().size.toDouble() }, 0.5),
                sameSiteNavShare = share(inSessionPairs) { (a, b) -> a.site == b.site },
                top10Share = topShare(loads, 10),
                top50Share = topShare(loads, 50),
                top10NonSearchShare = topShare(nonSearch, 10),
                revisitShare = revisitShare(loads, dayStarts),
                revisitNonSearchShare = revisitShare(nonSearch, dayStarts),
                homePageShare = share(urls) { it.rawPath.isNullOrEmpty() || it.rawPath == "/" },
                queryStringShare = share(urls) { !it.rawQuery.isNullOrEmpty() },
                pathDepthMedian = quantile(urls.map { u -> pathDepth(u).toDouble() }, 0.5),
                topEngineShare = engineCounts.values.maxOrNull()?.let { it.toDouble() / serps.size } ?: Double.NaN,
                searchNoClickShare = searchNoClickShare(loads),
                reSearchShare = reSearchShare(serps),
                firstLoadMinutesAfterStartMedian = quantile(
                    firstLoadOffsets(loads, dayStarts, recording.allowedStart).map { it / 60_000.0 }, 0.5,
                ),
                daysStartingOnTheHour = share(firstLoadOffsets(loads, dayStarts, recording.allowedStart)) {
                    it < ON_THE_HOUR_MS
                },
                weekendToWeekdayRatio = weekendRatio(loadsByDay, dayStarts),
                standaloneDnsShare = standaloneDnsShare(recording.events, loads),
                afterCloseCount = afterClose.size,
                afterCloseMaxS = (afterClose.maxOrNull() ?: 0L) / 1000.0,
            )
        }

        /** Local midnights of each simulated day; DST-safe because Calendar does the stepping. */
        private fun dayStarts(startEpochMs: Long, days: Int): LongArray = LongArray(days) { i ->
            Calendar.getInstance().apply {
                timeInMillis = startEpochMs
                add(Calendar.DAY_OF_MONTH, i)
            }.timeInMillis
        }

        private fun dayIndex(dayStarts: LongArray, atMs: Long): Int? {
            if (dayStarts.isEmpty() || atMs < dayStarts[0]) return null
            val found = dayStarts.indexOfLast { it <= atMs }
            return found.takeIf { it >= 0 }
        }

        private fun sessions(loads: List<StreamEvent>): List<List<StreamEvent>> {
            val result = mutableListOf<MutableList<StreamEvent>>()
            for (load in loads) {
                val current = result.lastOrNull()
                if (current == null || load.atMs - current.last().atMs > SESSION_GAP_MS) {
                    result += mutableListOf(load)
                } else {
                    current += load
                }
            }
            return result
        }

        /**
         * For a request made while the window was closed, how long after the latest close it came;
         * null for a request inside the window. Closes are at [allowedEnd] o'clock (24 is midnight).
         */
        internal fun msSinceClose(atMs: Long, allowedStart: Int, allowedEnd: Int): Long? {
            val cal = Calendar.getInstance().apply { timeInMillis = atMs }
            if (AllowedHours.isWithin(cal.get(Calendar.HOUR_OF_DAY), allowedStart, allowedEnd)) return null
            val close = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.HOUR_OF_DAY, allowedEnd)
            }
            if (close.timeInMillis > atMs) close.add(Calendar.DAY_OF_MONTH, -1)
            return atMs - close.timeInMillis
        }

        /** The most common one-second bucket of [gaps], with its count. */
        private fun modalBucket(gaps: List<Double>): Map.Entry<Long, Int>? =
            gaps.groupingBy { (it / 1000).toLong() }.eachCount().maxByOrNull { it.value }

        private fun topShare(loads: List<StreamEvent>, top: Int): Double {
            if (loads.isEmpty()) return Double.NaN
            val counts = loads.groupingBy { it.site }.eachCount().values.sortedDescending()
            return counts.take(top).sum().toDouble() / loads.size
        }

        private fun revisitShare(loads: List<StreamEvent>, dayStarts: LongArray): Double {
            val sitesByDay = loads.groupBy { dayIndex(dayStarts, it.atMs) }
            var revisits = 0
            var total = 0
            for (day in 1 until dayStarts.size) {
                val seen = ((day - REVISIT_LOOKBACK_DAYS).coerceAtLeast(0) until day)
                    .flatMapTo(HashSet()) { d -> sitesByDay[d].orEmpty().map { it.site } }
                for (load in sitesByDay[day].orEmpty()) {
                    total++
                    if (load.site in seen) revisits++
                }
            }
            return if (total == 0) Double.NaN else revisits.toDouble() / total
        }

        private fun pathDepth(uri: URI): Int =
            uri.rawPath.orEmpty().split('/').count { it.isNotEmpty() }

        private fun searchNoClickShare(loads: List<StreamEvent>): Double {
            var searches = 0
            var withoutClick = 0
            for ((i, load) in loads.withIndex()) {
                if (load.kind != LoadKind.SERP) continue
                searches++
                val clicked = loads.subList(i + 1, loads.size)
                    .takeWhile { it.kind != LoadKind.SERP }
                    .any { it.fromSearch }
                if (!clicked) withoutClick++
            }
            return if (searches == 0) Double.NaN else withoutClick.toDouble() / searches
        }

        private fun reSearchShare(serps: List<StreamEvent>): Double {
            if (serps.isEmpty()) return Double.NaN
            val reSearched = serps.zipWithNext().count { (a, b) -> b.atMs - a.atMs <= RE_SEARCH_WINDOW_MS }
            return reSearched.toDouble() / serps.size
        }

        /** Milliseconds from each active day's window opening to its first page load. */
        private fun firstLoadOffsets(loads: List<StreamEvent>, dayStarts: LongArray, allowedStart: Int): List<Double> =
            loads.groupBy { dayIndex(dayStarts, it.atMs) }
                .mapNotNull { (day, dayLoads) ->
                    day ?: return@mapNotNull null
                    val opens = Calendar.getInstance().apply {
                        timeInMillis = dayStarts[day]
                        set(Calendar.HOUR_OF_DAY, allowedStart)
                    }.timeInMillis
                    val first = dayLoads.filter { it.atMs >= opens }.minOfOrNull { it.atMs } ?: return@mapNotNull null
                    (first - opens).toDouble()
                }

        private fun weekendRatio(loadsByDay: IntArray, dayStarts: LongArray): Double {
            val (weekend, weekday) = loadsByDay.indices.partition { day ->
                val dow = Calendar.getInstance().apply { timeInMillis = dayStarts[day] }.get(Calendar.DAY_OF_WEEK)
                dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            }
            if (weekend.isEmpty() || weekday.isEmpty()) return Double.NaN
            val weekendMean = weekend.map { loadsByDay[it] }.average()
            val weekdayMean = weekday.map { loadsByDay[it] }.average()
            return if (weekdayMean == 0.0) Double.NaN else weekendMean / weekdayMean
        }

        private fun standaloneDnsShare(events: List<StreamEvent>, sortedLoads: List<StreamEvent>): Double {
            val lookups = events.filter { it.kind == LoadKind.DNS }
            if (lookups.isEmpty()) return Double.NaN
            val loadTimes = sortedLoads.map { it.atMs }
            val standalone = lookups.count { lookup ->
                val i = loadTimes.binarySearch(lookup.atMs).let { if (it < 0) -it - 1 else it }
                val near = listOfNotNull(loadTimes.getOrNull(i - 1), loadTimes.getOrNull(i))
                near.none { kotlin.math.abs(it - lookup.atMs) <= DNS_BURST_WINDOW_MS }
            }
            return standalone.toDouble() / lookups.size
        }

        /** Nearest-rank quantile; NaN for an empty list. */
        internal fun quantile(values: List<Double>, q: Double): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.sorted()
            val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        private fun cv(values: List<Double>): Double {
            if (values.size < 2) return Double.NaN
            val mean = values.average()
            if (mean == 0.0) return Double.NaN
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            return sqrt(variance) / mean
        }

        private fun <T> share(values: List<T>, predicate: (T) -> Boolean): Double =
            if (values.isEmpty()) Double.NaN else values.count(predicate).toDouble() / values.size
    }
}
