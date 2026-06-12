package com.fauxx.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build guard for #183. After M1 (#168 / #169) routed the synthetic search path onto the Chromium
 * WebView (PhantomWebViewPool), no engine module consumes OkHttp any more. The provided OkHttpClient
 * still carries a constant JA3/JA4 TLS fingerprint and a fixed header order, so any new OkHttp
 * request path under app/src/main would silently re-open the fingerprint tell #168 / #169 closed.
 *
 * This test fails if real (non-comment) code under src/main reintroduces an OkHttp request: an
 * `okhttp3.Request` reference (import or fully-qualified use) or a `.newCall(` execution. If OkHttp
 * is genuinely needed again, route synthetic/search traffic through the WebView for the TLS path,
 * then update this guard with the reviewed exception.
 */
class OkHttpOrphanGuardTest {

    @Test
    fun `no OkHttp request path is reintroduced under src main`() {
        val srcDir = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/java from ${File(".").absolutePath}")

        val offenders = mutableListOf<String>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, raw ->
                    val line = raw.trim()
                    // Skip comment / KDoc lines so documentation that *names* the forbidden
                    // patterns (including this guard's own rationale) can never trip the check;
                    // only actual code is inspected.
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    if (line.contains("okhttp3.Request") || line.contains(".newCall(")) {
                        offenders += "${file.path}:${idx + 1}: $line"
                    }
                }
            }

        assertTrue(
            "OkHttp request path reintroduced under src/main (#183). Route synthetic/search " +
                "traffic through the Chromium WebView, not OkHttp, to preserve the JA3/JA4 match. " +
                "Offending lines:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
