package com.fauxx.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the in-app GitHub Issue Form deep-link builder. No Android
 * dependencies — runs directly under JUnit without Robolectric.
 *
 * The contract: short traces embed fully into a single URL; long traces embed a head
 * with a truncation marker and signal that the full content must be supplied via
 * clipboard. Manufacturer prefix dedup keeps device strings readable, and the flavor
 * mapping covers the `BuildConfig.FLAVOR` values that ship plus an unknown fallback.
 */
class CrashReportUrlBuilderTest {

    // --- formatDevice ---

    @Test
    fun `formatDevice deduplicates when model already starts with manufacturer`() {
        assertEquals(
            "Google Pixel 7 Pro",
            CrashReportUrlBuilder.formatDevice("google", "Google Pixel 7 Pro")
        )
    }

    @Test
    fun `formatDevice keeps both parts when model does not start with manufacturer`() {
        assertEquals(
            "Samsung SM-S928U",
            CrashReportUrlBuilder.formatDevice("samsung", "SM-S928U")
        )
    }

    @Test
    fun `formatDevice prefix dedup is case-insensitive`() {
        // Build.MANUFACTURER is conventionally lowercase ("google"); MODEL casing varies.
        // The dedup must trigger when the manufacturer prefix is present in any case.
        assertEquals(
            "google Pixel 7",
            CrashReportUrlBuilder.formatDevice("GOOGLE", "google Pixel 7")
        )
        assertEquals(
            "Google Pixel 7 Pro",
            CrashReportUrlBuilder.formatDevice("google", "Google Pixel 7 Pro")
        )
    }

    @Test
    fun `formatDevice handles empty manufacturer`() {
        assertEquals(
            "SM-S928U",
            CrashReportUrlBuilder.formatDevice("", "SM-S928U")
        )
    }

    @Test
    fun `formatDevice handles empty model`() {
        assertEquals(
            "Google",
            CrashReportUrlBuilder.formatDevice("google", "")
        )
    }

    // --- mapFlavor ---

    @Test
    fun `mapFlavor maps known build flavors to form dropdown values`() {
        assertEquals("Full", CrashReportUrlBuilder.mapFlavor("full"))
        assertEquals("Play", CrashReportUrlBuilder.mapFlavor("play"))
    }

    @Test
    fun `mapFlavor case-insensitive`() {
        assertEquals("Full", CrashReportUrlBuilder.mapFlavor("FULL"))
        assertEquals("Play", CrashReportUrlBuilder.mapFlavor("Play"))
    }

    @Test
    fun `mapFlavor unknown falls back to Not sure`() {
        assertEquals("Not sure", CrashReportUrlBuilder.mapFlavor(""))
        assertEquals("Not sure", CrashReportUrlBuilder.mapFlavor("dev"))
    }

    // --- build: embedding ---

    @Test
    fun `build embeds short trace fully and returns Embedded`() {
        val trace = "short stack trace\nat com.fauxx.Foo.bar(Foo.kt:10)"
        val result = CrashReportUrlBuilder.build(
            device = "Google Pixel 7 Pro",
            androidVersion = "14",
            appVersion = "0.2.7 (207)",
            flavor = "Full",
            trace = trace
        )
        assertTrue("expected Embedded, got $result", result is CrashReportUrlBuilder.Result.Embedded)
        // URL contains the encoded trace verbatim — the head wasn't truncated.
        assertTrue(result.url.contains("stack_trace="))
        assertFalse(
            "embedded URL must not contain the truncation marker",
            result.url.contains("truncated")
        )
    }

    @Test
    fun `build url contains all four short fields as separate query params`() {
        val result = CrashReportUrlBuilder.build(
            device = "Google Pixel 7 Pro",
            androidVersion = "14",
            appVersion = "0.2.7 (207)",
            flavor = "Full",
            trace = "short"
        )
        // Each field becomes a URL-encoded query param — verify they all show up.
        assertTrue(result.url.contains("device=Google+Pixel+7+Pro"))
        assertTrue(result.url.contains("android_version=14"))
        assertTrue(result.url.contains("app_version=0.2.7+%28207%29"))
        assertTrue(result.url.contains("flavor=Full"))
    }

    @Test
    fun `build always targets the crash_report form template`() {
        val result = CrashReportUrlBuilder.build(
            device = "Google Pixel 7 Pro",
            androidVersion = "14",
            appVersion = "0.2.7 (207)",
            flavor = "Full",
            trace = ""
        )
        assertTrue(result.url.contains("template=crash_report.yml"))
    }

    // --- build: truncation ---

    @Test
    fun `build truncates long trace and returns Truncated with full content`() {
        // Build a trace longer than the URL budget allows under URL encoding.
        // Each "%0A%20%20" line break encodes to ~9 chars per raw 4 — so 5000 raw chars
        // of mostly-whitespace produces ~11000 encoded chars, well over the 7000 limit.
        val trace = "stack frame line ".repeat(800) // ~13600 raw chars
        val result = CrashReportUrlBuilder.build(
            device = "Google Pixel 7 Pro",
            androidVersion = "14",
            appVersion = "0.2.7 (207)",
            flavor = "Full",
            trace = trace
        )
        assertTrue("expected Truncated, got $result", result is CrashReportUrlBuilder.Result.Truncated)
        result as CrashReportUrlBuilder.Result.Truncated
        assertEquals("full trace must be preserved in Truncated.fullTrace", trace, result.fullTrace)
        assertTrue(
            "truncated URL must contain the truncation marker (URL-encoded)",
            result.url.contains("truncated") ||
                result.url.contains("%E2%80%A6truncated") // … truncated
        )
    }

    @Test
    fun `build keeps URL under the configured max length even for huge traces`() {
        val huge = "x".repeat(100_000)
        val result = CrashReportUrlBuilder.build(
            device = "Google Pixel 7 Pro",
            androidVersion = "14",
            appVersion = "0.2.7 (207)",
            flavor = "Full",
            trace = huge,
            maxUrlLength = 7000
        )
        assertTrue("expected Truncated for huge trace", result is CrashReportUrlBuilder.Result.Truncated)
        assertTrue(
            "URL length ${result.url.length} must be <= maxUrlLength 7000",
            result.url.length <= 7000
        )
    }

    @Test
    fun `build respects custom max URL length`() {
        // With a very tight budget the trace gets truncated even when it would otherwise fit.
        val trace = "x".repeat(500) // raw 500 chars; encoded length = 500 (no special chars)
        val result = CrashReportUrlBuilder.build(
            device = "Google Pixel 7 Pro",
            androidVersion = "14",
            appVersion = "0.2.7 (207)",
            flavor = "Full",
            trace = trace,
            maxUrlLength = 250 // tighter than the base URL — forces truncation regardless of trace size
        )
        assertTrue(
            "tight budget should force Truncated, got $result",
            result is CrashReportUrlBuilder.Result.Truncated
        )
    }
}
