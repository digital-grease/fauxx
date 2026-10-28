package com.fauxx.shape

import java.util.Locale

/**
 * Renders a [StreamShape] as a Markdown table next to the real-history reference values from
 * #322 (aggregate statistics of the one browsing history shared in #270).
 *
 * The reference is a sample of one person, so it says what direction to move in, not a target to
 * hit: #322 asks for every parameter to be drawn per persona from a range.
 */
internal object ShapeReport {

    private class Row(val metric: String, val value: String, val reference: String)

    fun markdown(title: String, shape: StreamShape, notes: List<String> = emptyList()): String {
        val rows = listOf(
            Row("Successful actions per allowed hour", num(shape.actionsPerActiveHour, 1), "the tier's displayed rate"),
            Row("Page loads per day (median / p90)", "${num(shape.loadsPerDayMedian, 0)} / ${num(shape.loadsPerDayP90, 0)}", "24 / 98"),
            Row("Daily total variation (CV)", num(shape.dailyCv, 2), "varies widely"),
            Row("Gaps under 10 s", pct(shape.gapsUnder10s), "48%"),
            Row("Gap median / p90", "${secs(shape.gapMedianS)} / ${secs(shape.gapP90S)}", "-"),
            Row("In-session gap CV", num(shape.inSessionGapCv, 2), "-"),
            Row("Most common in-session gap", "${pct(shape.modalGapShare)} at ${secs(shape.modalGapS)}", "-"),
            Row("Most common gap between logged actions", "${pct(shape.modalActionGapShare)} at ${secs(shape.modalActionGapS)}", "-"),
            Row("Sessions per day (median)", num(shape.sessionsPerDayMedian, 0), "about 4"),
            Row("Session size in loads (median / p90)", "${num(shape.sessionSizeMedian, 0)} / ${num(shape.sessionSizeP90, 0)}", "4 / 24"),
            Row("Sites per session (median)", num(shape.sitesPerSessionMedian, 0), "2"),
            Row("In-session navigations on the same site", pct(shape.sameSiteNavShare), "74%"),
            Row("Top 10 / top 50 sites' share of loads", "${pct(shape.top10Share)} / ${pct(shape.top50Share)}", "39% / 61%"),
            Row("Top 10 sites' share, search pages left out", pct(shape.top10NonSearchShare), "-"),
            Row("Loads to sites seen in the previous 14 days", pct(shape.revisitShare), "56%"),
            Row("The same, search pages left out", pct(shape.revisitNonSearchShare), "-"),
            Row("Home pages", pct(shape.homePageShare), "6%"),
            Row("Loads with a query string", pct(shape.queryStringShare), "64%"),
            Row("Path depth (median)", num(shape.pathDepthMedian, 0), "2"),
            Row("Searches on the most-used engine", pct(shape.topEngineShare), "100%"),
            Row("Searches with no result clicked", pct(shape.searchNoClickShare), "55%"),
            Row("Searches followed by another within 2 min", pct(shape.reSearchShare), "43%"),
            Row("First load after the window opens (median)", "${num(shape.firstLoadMinutesAfterStartMedian, 1)} min", "ramp over about 4 h"),
            Row("Days starting within a minute of the window opening", pct(shape.daysStartingOnTheHour), "-"),
            Row("Weekend day / weekday volume", num(shape.weekendToWeekdayRatio, 2), "about 2"),
            Row("DNS-module lookups with no page load within 2 s", pct(shape.standaloneDnsShare), "- (real lookups come with page loads)"),
            Row("Requests after the window closed (count / latest)", "${shape.afterCloseCount} / ${secs(shape.afterCloseMaxS)}", "-"),
        )
        return buildString {
            appendLine("## $title")
            appendLine()
            appendLine("${shape.days} simulated days.")
            for (note in notes) appendLine("- $note")
            appendLine()
            appendLine("| Metric | Fauxx | Real history (#270) |")
            appendLine("|---|---|---|")
            for (row in rows) appendLine("| ${row.metric} | ${row.value} | ${row.reference} |")
        }
    }

    private fun num(value: Double, decimals: Int): String =
        if (value.isNaN()) "n/a" else String.format(Locale.US, "%.${decimals}f", value)

    private fun pct(value: Double): String =
        if (value.isNaN()) "n/a" else String.format(Locale.US, "%.0f%%", value * 100)

    private fun secs(value: Double): String =
        if (value.isNaN()) "n/a" else String.format(Locale.US, "%.0f s", value)
}
