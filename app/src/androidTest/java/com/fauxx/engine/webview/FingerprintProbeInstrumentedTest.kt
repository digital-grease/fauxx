package com.fauxx.engine.webview

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.device.DeviceDeriver
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.model.SyntheticPersona
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Measures what a page actually sees from a pooled phantom WebView: the request headers that reach
 * a server (including `Sec-CH-UA*` client hints on the navigation and on subresources), and what
 * JavaScript reads from `navigator`, `userAgentData`, the viewport, WebGL and canvas, in the main
 * frame and in a same-origin iframe, both before and after [JSInjector]'s script lands.
 *
 * This is spike s3's Gates A-D (`.devloop/spikes/s3-persona-device-coherence.md`) run through the
 * real [PhantomWebViewPool] and [PhantomWebViewClient], so it observes the configuration the
 * engine ships rather than a hand-built WebView. It serves the probe page from an in-process
 * server on 127.0.0.1, which is a potentially trustworthy origin, so Chromium sends client hints to
 * it as it would over https (the debug network security config permits cleartext to loopback only).
 *
 * Output goes to logcat under [TAG], one `KEY=value` line per observation, and the test FAILS on
 * any giveaway it knows how to detect (see [findGiveaways]): the app's package name reaching a
 * server, a User-Agent that disagrees with its own client hints, `eval`/WebAssembly/Worker/canvas
 * tampering, own properties on `navigator`, a 0x0 viewport, and main-frame/iframe disagreement.
 * Checks that depend on a WebView feature are skipped where the device lacks it.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintProbeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun probeWhatPagesSee() = runBlocking {
        var defaultUa: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val probe = WebView(context)
            defaultUa = probe.settings.userAgentString
            probe.destroy()
        }
        log("defaultUserAgent", defaultUa)
        log("webviewPackage", WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" })
        for (feature in listOf(
            WebViewFeature.USER_AGENT_METADATA,
            WebViewFeature.DOCUMENT_START_SCRIPT,
            WebViewFeature.MULTI_PROFILE,
            WebViewFeature.PROXY_OVERRIDE,
        )) {
            log("feature.$feature", WebViewFeature.isFeatureSupported(feature).toString())
        }

        runPass("nodevice", device = null)

        val persona = SyntheticPersona(
            id = "fingerprint-probe",
            name = "probe",
            ageRange = "25-34",
            profession = "probe",
            region = "probe",
            interests = emptySet(),
            activeUntil = System.currentTimeMillis() + 86_400_000L,
        )
        runPass("persona", device = DeviceDeriver(context).mobileFor(persona))
    }

    @Test
    fun trackingPixelFiresOnlyWhenImagesAreOn() = runBlocking {
        for (loadImages in listOf(false, true)) {
            val server = ProbeServer().apply { start() }
            val prefs = object : PhantomBrowsingPrefs {
                override fun loadImages(): Boolean = loadImages
            }
            val pool = PhantomWebViewPool(
                context, mockk<DomainBlocklist>(relaxed = true), PersonaJarStore(),
                PhantomIdentityProvider.NONE, prefs,
            )
            try {
                pool.initialize()
                val webView = pool.acquire()
                try {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        webView.loadUrl("http://127.0.0.1:${server.port}/page", SYNTHETIC_WEBVIEW_HEADERS)
                    }
                    server.reports.await(20, TimeUnit.SECONDS)
                } finally {
                    pool.release(webView)
                }
                val pixelFetched = server.requests.any { it.startsWith("GET /pixel.gif") }
                log("images.loadImages=$loadImages.pixelFetched", pixelFetched.toString())
                assertEquals("tracking pixel fetched with loadImages=$loadImages", loadImages, pixelFetched)
            } finally {
                pool.destroy()
                server.stop()
            }
        }
    }

    private suspend fun runPass(pass: String, device: DeviceProfile?) {
        val server = ProbeServer().apply { start() }
        val pool = PhantomWebViewPool(context, mockk<DomainBlocklist>(relaxed = true), PersonaJarStore())
        try {
            pool.initialize()
            device?.let { pool.setDevice(it); log("$pass.personaModel", "${it.model} / Android ${it.platformVersion}") }
            val webView = pool.acquire()
            log("$pass.presentedUserAgent", pool.presentedUserAgent())
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    webView.loadUrl("http://127.0.0.1:${server.port}/page", SYNTHETIC_WEBVIEW_HEADERS)
                }
                val complete = server.reports.await(20, TimeUnit.SECONDS)
                log("$pass.reportsComplete", complete.toString())
            } finally {
                pool.release(webView)
            }
            server.requests.forEach { log("$pass.request", it) }
            server.bodies.forEach { log("$pass.js", it) }
            val problems = findGiveaways(server, pool.presentedUserAgent(), device)
            problems.forEach { log("$pass.GIVEAWAY", it) }
            assertTrue("[$pass] fingerprint giveaways:\n" + problems.joinToString("\n"), problems.isEmpty())
        } finally {
            pool.destroy()
            server.stop()
        }
    }

    /**
     * Every contradiction this test has caught and the engine has since fixed. Returned as a list
     * rather than asserted one at a time so a regression report shows all of them at once.
     */
    private fun findGiveaways(server: ProbeServer, presentedUa: String?, device: DeviceProfile?): List<String> {
        val problems = mutableListOf<String>()
        val requests = server.requests.toList()
        fun header(request: String, name: String): String? =
            request.split(" | ").firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

        // The package name must never reach a server (X-Requested-With), where WebView lets us stop it.
        if (supported(WebViewFeature.MULTI_PROFILE) && supported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) {
            requests.filter { it.contains("com.fauxx", ignoreCase = true) }
                .forEach { problems += "package name sent: ${it.substringBefore(" HTTP")}" }
        }

        // UA and client hints must name the same browser on every request.
        for (r in requests) {
            val ua = header(r, "User-Agent") ?: continue
            val brands = header(r, "sec-ch-ua") ?: continue
            if (brands.contains(BrowserIdentity.WEBVIEW_BRAND) != ua.contains("; wv")) {
                problems += "UA and Sec-CH-UA disagree on ${r.substringBefore(" HTTP")}: ua=$ua brands=$brands"
            }
        }

        if (supported(WebViewFeature.USER_AGENT_METADATA)) {
            if (presentedUa == null) problems += "no identity presented although USER_AGENT_METADATA is supported"
            requests.mapNotNull { header(it, "User-Agent") }.filter { it != presentedUa }
                .distinct().forEach { problems += "request UA differs from the presented one: $it" }
            requests.mapNotNull { header(it, "sec-ch-ua") }.filter { !it.contains(BrowserIdentity.CHROME_BRAND) }
                .distinct().forEach { problems += "Sec-CH-UA is not Chrome's: $it" }
            val expectedModel = device?.model ?: BrowserIdentity.DEFAULT_MODEL
            requests.filter { it.startsWith("GET /sub.js") }.mapNotNull { header(it, "sec-ch-ua-model") }
                .filter { it != "\"$expectedModel\"" }
                .forEach { problems += "Sec-CH-UA-Model is $it, expected \"$expectedModel\"" }
        }

        val reports = server.bodies.map { JSONObject(it) }.associateBy { it.getString("tag") }
        val late = reports["late"]
        val frame = reports["frame"]
        if (late == null || frame == null) {
            problems += "missing page reports: ${reports.keys}"
            return problems
        }
        if (late.opt("eval") != 2) problems += "eval('1+1') returned ${late.opt("eval")}"
        if (late.optString("wasmType") != "object") problems += "typeof WebAssembly is ${late.optString("wasmType")}"
        if (late.optBoolean("workerNative") != true) problems += "Worker is not native"
        if (late.opt("offscreenWidth") != 300) problems += "OffscreenCanvas(300,150).width is ${late.opt("offscreenWidth")}"
        if (late.opt("canvasReadsDiffer") != 0) problems += "two canvas reads differ in ${late.opt("canvasReadsDiffer")} bytes"
        val ownProps = late.optJSONArray("ownNavigatorProps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        ownProps.filter { it != "globalPrivacyControl" }.forEach { problems += "navigator carries own property $it" }
        if (late.opt("gpc") != true) problems += "navigator.globalPrivacyControl is ${late.opt("gpc")}"
        val inner = late.optJSONArray("inner")
        if (inner == null || inner.optInt(0) <= 0 || inner.optInt(1) <= 0) problems += "viewport is degenerate: $inner"
        for (key in listOf("hardwareConcurrency", "deviceMemory", "wasmType")) {
            if (late.opt(key)?.toString() != frame.opt(key)?.toString()) {
                problems += "main frame and iframe disagree on $key: ${late.opt(key)} vs ${frame.opt(key)}"
            }
        }
        return problems
    }

    private fun supported(feature: String) = WebViewFeature.isFeatureSupported(feature)

    private fun log(key: String, value: String?) {
        // logcat truncates a single entry near 4 KB, so long values are chunked.
        (value ?: "null").chunked(3000).forEachIndexed { i, part ->
            Log.i(TAG, if (i == 0) "$key=$part" else "$key+=$part")
        }
    }

    /** Minimal HTTP/1.1 server: one request per connection, records everything it is sent. */
    private class ProbeServer {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Released once the main frame's late report and the iframe's report have both arrived. */
        val reports = CountDownLatch(2)

        fun start() {
            thread(isDaemon = true, name = "fingerprint-probe") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { handle(client) } }
                }
            }
        }

        fun stop() = runCatching { socket.close() }

        private fun handle(client: Socket) = client.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            requests += (listOf(requestLine) + headers).joinToString(" | ")

            val path = requestLine.split(" ").getOrElse(1) { "/" }
            if (requestLine.startsWith("POST")) {
                val length = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val body = CharArray(length).also { var read = 0; while (read < length) { val n = reader.read(it, read, length - read); if (n < 0) break; read += n } }
                bodies += String(body)
                if (path.contains("tag=late") || path.contains("tag=frame")) reports.countDown()
                respond(s, "text/plain", "ok")
                return
            }
            when {
                path.startsWith("/page") -> respond(s, "text/html", pageHtml(framed = false), acceptCh = true)
                path.startsWith("/frame") -> respond(s, "text/html", pageHtml(framed = true))
                path.startsWith("/sub.js") -> respond(s, "application/javascript", "window.__sub = 1;")
                path.startsWith("/pixel.gif") -> respond(s, "image/gif", "GIF89a")
                else -> respond(s, "text/plain", "not found", status = "404 Not Found")
            }
        }

        private fun respond(
            s: Socket,
            type: String,
            body: String,
            status: String = "200 OK",
            acceptCh: Boolean = false,
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $type\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                if (acceptCh) append("Accept-CH: $HIGH_ENTROPY_HINTS\r\n")
                append("Connection: close\r\n\r\n")
            }
            s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        }

        private fun pageHtml(framed: Boolean): String {
            val tail = if (framed) {
                "setTimeout(function(){collect('frame');}, 800);"
            } else {
                "collect('early'); setTimeout(function(){collect('late');}, 800);"
            }
            val body = if (framed) "" else "<img src=\"/pixel.gif\"><iframe src=\"/frame\"></iframe>"
            return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<script src=\"/sub.js\"></script><script>$COLLECT_JS\n$tail</script></head>" +
                "<body>$body</body></html>"
        }
    }

    companion object {
        private const val TAG = "FauxxFpProbe"

        private const val HIGH_ENTROPY_HINTS = "Sec-CH-UA-Model, Sec-CH-UA-Platform-Version, " +
            "Sec-CH-UA-Full-Version-List, Sec-CH-UA-Arch, Sec-CH-UA-Bitness, Sec-CH-UA-WoW64, " +
            "Sec-CH-UA-Form-Factors, Sec-CH-Device-Memory, Device-Memory"

        /** Page-side collector. Plain ES5-ish functions, no template literals, so Kotlin needs no escaping. */
        private val COLLECT_JS = """
            function collect(tag) {
              var r = { tag: tag };
              function tryIt(k, f) { try { r[k] = f(); } catch (e) { r[k] = 'err:' + e; } }
              tryIt('ua', function(){ return navigator.userAgent; });
              tryIt('uad', function(){ var d = navigator.userAgentData; return d ? { brands: d.brands, mobile: d.mobile, platform: d.platform } : null; });
              tryIt('hardwareConcurrency', function(){ return navigator.hardwareConcurrency; });
              tryIt('deviceMemory', function(){ return navigator.deviceMemory; });
              tryIt('ownNavigatorProps', function(){ return Object.getOwnPropertyNames(navigator); });
              tryIt('hcGetterSource', function(){ var d = Object.getOwnPropertyDescriptor(navigator, 'hardwareConcurrency'); return d && d.get ? String(d.get) : null; });
              tryIt('inner', function(){ return [innerWidth, innerHeight]; });
              tryIt('visualViewport', function(){ return window.visualViewport ? [visualViewport.width, visualViewport.height] : null; });
              tryIt('screen', function(){ return [screen.width, screen.height, screen.availWidth, screen.availHeight]; });
              tryIt('dpr', function(){ return devicePixelRatio; });
              tryIt('visibility', function(){ return document.visibilityState; });
              tryIt('wasmIn', function(){ return 'WebAssembly' in window; });
              tryIt('wasmType', function(){ return typeof WebAssembly; });
              // Deliberate: a constant expression, run to detect whether JSInjector has neutered eval
              // (s3 gap 2). A real Chrome returns 2.
              tryIt('eval', function(){ return eval('1+1'); });
              tryIt('workerNative', function(){ return String(window.Worker).indexOf('[native code]') >= 0; });
              tryIt('gpc', function(){ return navigator.globalPrivacyControl; });
              tryIt('webdriver', function(){ return navigator.webdriver; });
              tryIt('languages', function(){ return navigator.languages; });
              tryIt('maxTouchPoints', function(){ return navigator.maxTouchPoints; });
              tryIt('windowChrome', function(){ return typeof window.chrome; });
              tryIt('offscreenWidth', function(){ return new OffscreenCanvas(300, 150).width; });
              tryIt('canvasReadsDiffer', function(){
                var c = document.createElement('canvas'); c.width = 60; c.height = 20;
                var x = c.getContext('2d'); x.fillText('fauxx', 2, 12);
                var a = x.getImageData(0, 0, 60, 20).data, b = x.getImageData(0, 0, 60, 20).data, n = 0;
                for (var i = 0; i < a.length; i++) { if (a[i] !== b[i]) n++; }
                return n;
              });
              tryIt('webgl', function(){
                var gl = document.createElement('canvas').getContext('webgl');
                if (!gl) return null;
                var d = gl.getExtension('WEBGL_debug_renderer_info');
                return { vendor: gl.getParameter(gl.VENDOR), renderer: gl.getParameter(gl.RENDERER),
                  unmaskedVendor: d ? gl.getParameter(d.UNMASKED_VENDOR_WEBGL) : null,
                  unmaskedRenderer: d ? gl.getParameter(d.UNMASKED_RENDERER_WEBGL) : null };
              });
              var send = function() { fetch('/report?tag=' + tag, { method: 'POST', body: JSON.stringify(r) }); };
              if (navigator.userAgentData && navigator.userAgentData.getHighEntropyValues) {
                navigator.userAgentData.getHighEntropyValues(['model', 'platformVersion', 'fullVersionList', 'architecture', 'bitness', 'formFactors'])
                  .then(function(h){ r.highEntropy = h; send(); }, function(e){ r.highEntropy = 'err:' + e; send(); });
              } else { send(); }
            }
        """.trimIndent()
    }
}
