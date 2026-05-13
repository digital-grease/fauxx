package com.fauxx.util

import java.net.URLEncoder

/**
 * Builds a pre-filled GitHub Issue Form URL for `crash_report.yml` so a user tapping
 * "File a GitHub Issue" in the app lands on a form that's already 80% complete.
 *
 * The form's field IDs (`device`, `android_version`, `app_version`, `flavor`,
 * `stack_trace`) line up with the URL query parameters here — GitHub renders each
 * pre-filled value into the corresponding field on load.
 *
 * Pure Kotlin: no Android dependencies. Tests can call directly without Robolectric.
 */
object CrashReportUrlBuilder {

    private const val BASE_URL =
        "https://github.com/digital-grease/fauxx/issues/new?template=crash_report.yml"

    /** Conservative max URL length before GitHub starts 500-ing on long body params. */
    private const val DEFAULT_MAX_URL_LENGTH = 7000

    /** Raw character budget for the head of a truncated trace. URL encoding inflates ~2.5x. */
    private const val TRUNCATED_HEAD_CHARS = 2000

    private const val TRUNCATION_MARKER =
        "\n\n[…truncated — full trace copied to clipboard, paste below]"

    /**
     * Format `Build.MANUFACTURER + " " + Build.MODEL` with prefix dedup so
     * "Google Pixel 7 Pro" stays clean and "samsung Galaxy S24" doesn't become
     * "samsung samsung Galaxy S24". Capitalizes the manufacturer for readability
     * since `Build.MANUFACTURER` is conventionally lowercase ("samsung", "google").
     */
    fun formatDevice(manufacturer: String, model: String): String {
        val mfg = manufacturer.trim()
        val mdl = model.trim()
        return when {
            mfg.isEmpty() -> mdl
            mdl.isEmpty() -> mfg.replaceFirstChar { it.titlecase() }
            mdl.startsWith(mfg, ignoreCase = true) -> mdl
            else -> "${mfg.replaceFirstChar { it.titlecase() }} $mdl"
        }
    }

    /**
     * Map `BuildConfig.FLAVOR` to the value the form's Build-flavor dropdown expects.
     * Anything we don't recognise falls back to "Not sure" so the auto-labeler skips
     * applying a flavor label rather than guessing wrong.
     */
    fun mapFlavor(buildConfigFlavor: String): String = when (buildConfigFlavor.lowercase()) {
        "play" -> "Play"
        "full" -> "Full"
        else -> "Not sure"
    }

    /**
     * Build the issue-form URL. Returns [Result.Embedded] when the full trace fits
     * within the URL budget, or [Result.Truncated] when the trace head is embedded
     * and the full content must be supplied via clipboard.
     */
    fun build(
        device: String,
        androidVersion: String,
        appVersion: String,
        flavor: String,
        trace: String,
        maxUrlLength: Int = DEFAULT_MAX_URL_LENGTH
    ): Result {
        val shortFieldsUrl = BASE_URL +
            "&device=${enc(device)}" +
            "&android_version=${enc(androidVersion)}" +
            "&app_version=${enc(appVersion)}" +
            "&flavor=${enc(flavor)}"

        val traceParamOverhead = "&stack_trace=".length
        val remaining = maxUrlLength - shortFieldsUrl.length - traceParamOverhead

        val encodedFullTrace = enc(trace)
        if (encodedFullTrace.length <= remaining) {
            return Result.Embedded("$shortFieldsUrl&stack_trace=$encodedFullTrace")
        }

        // Truncate the raw trace, append the marker, encode, and verify it fits.
        // We deliberately work in raw chars (TRUNCATED_HEAD_CHARS) rather than
        // encoded chars so the truncation point is predictable for users — the
        // visible content is the first N chars of their stack trace.
        val headRaw = trace.take(TRUNCATED_HEAD_CHARS) + TRUNCATION_MARKER
        val encodedHead = enc(headRaw)
        // Defensive: if even the head + marker overflows the budget (unlikely with
        // 2000 raw chars), shrink further by ~25% rather than producing an oversize URL.
        val finalHead = if (encodedHead.length <= remaining) {
            encodedHead
        } else {
            val safeRawLen = (TRUNCATED_HEAD_CHARS * 3) / 4
            enc(trace.take(safeRawLen) + TRUNCATION_MARKER)
        }
        return Result.Truncated(
            url = "$shortFieldsUrl&stack_trace=$finalHead",
            fullTrace = trace
        )
    }

    // Use the (String, String) overload — the (String, Charset) overload is API 33+
    // and our minSdk is 26. UTF-8 is universally available.
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    sealed class Result {
        abstract val url: String

        /** Full trace fit in the URL — no clipboard step needed. */
        data class Embedded(override val url: String) : Result()

        /** Head embedded, full trace must be supplied via clipboard. */
        data class Truncated(override val url: String, val fullTrace: String) : Result()
    }
}
