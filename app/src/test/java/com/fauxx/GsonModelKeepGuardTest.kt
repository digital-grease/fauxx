package com.fauxx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the v0.2.6 dashboard regression: the corpus/blocklist Gson model classes were
 * not annotated `@Keep`, so R8 renamed/stripped their fields in the minified release build, Gson
 * had nothing to map the JSON keys onto, and the blocklist + crawl corpus silently deserialized
 * empty — while every debug build (unminified) worked fine, so it shipped.
 *
 * Each class below is deserialized from a bundled asset via reflective Gson and MUST carry
 * `@androidx.annotation.Keep`. This can't be verified by runtime reflection: `Keep` has CLASS
 * retention, so `isAnnotationPresent` returns false even when it's applied. The annotation
 * descriptor is, however, written into the `.class` file's RuntimeInvisibleAnnotations, so the
 * guard reads each compiled class off the test classpath and scans its bytecode for the
 * `androidx/annotation/Keep` descriptor — which works regardless of retention and needs no ASM.
 *
 * A minified-release instrumented test would verify the actual R8 outcome, but the release
 * androidTest APK needs the release signing key, so it can't run locally or on the per-PR CI;
 * this bytecode guard catches the same regression cause everywhere the unit suite runs.
 *
 * If you add a new Gson model deserialized from an asset, add it here AND annotate it `@Keep`.
 */
class GsonModelKeepGuardTest {

    /** Fully-qualified class name -> the asset it backs (for the failure message). */
    private val gsonAssetModels = mapOf(
        "com.fauxx.data.crawllist.CrawlEntryJson" to "crawl_urls.json (url/category)",
        "com.fauxx.data.crawllist.BlocklistJson" to "the domain blocklist asset",
        "com.fauxx.data.querybank.HarmfulQueriesJson" to "harmful_queries/<locale>.json",
        "com.fauxx.data.location.CityCoord" to "the cities asset (name/lat/lng/country)",
        "com.fauxx.targeting.layer1.RuleJson" to "the demographic distance-rules asset",
        "com.fauxx.targeting.layer3.PersonaTemplate" to "the persona templates asset",
    )

    @Test
    fun `every asset-backed Gson model is annotated @Keep so R8 cannot strip it in release`() {
        val unprotected = gsonAssetModels.keys.filterNot { classBytecodeReferencesKeep(it) }

        assertTrue(
            "These Gson model classes are deserialized from bundled assets but their compiled " +
                "bytecode does not reference @Keep. In a minified release, R8 renames/removes their " +
                "fields, Gson maps nothing, and the corpus/blocklist loads empty (the v0.2.6 dashboard " +
                "failure) — and debug builds won't catch it. Annotate each with " +
                "@androidx.annotation.Keep: $unprotected",
            unprotected.isEmpty(),
        )
    }

    @Test
    fun `the bytecode scan discriminates so the positive check is not vacuous`() {
        // Negative control: a class that is genuinely not @Keep-annotated must scan false, or a
        // broken byte search that always returns true would make the guard above pass for nothing.
        assertFalse(
            "kotlin.Unit is not @Keep-annotated; the scan must not report it as such",
            classBytecodeReferencesKeep("kotlin.Unit"),
        )
    }

    private fun classBytecodeReferencesKeep(fqn: String): Boolean {
        val resourcePath = "/" + fqn.replace('.', '/') + ".class"
        val bytes = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: throw AssertionError(
                "Compiled class not found on the test classpath: $fqn ($resourcePath). If the Gson " +
                    "model was renamed or moved, update this guard to match."
            )
        // ISO-8859-1 maps each byte 1:1 to a char, so a substring search over the class bytes is
        // exact; the annotation descriptor in the constant pool is `Landroidx/annotation/Keep;`.
        return String(bytes, Charsets.ISO_8859_1).contains("androidx/annotation/Keep")
    }
}
