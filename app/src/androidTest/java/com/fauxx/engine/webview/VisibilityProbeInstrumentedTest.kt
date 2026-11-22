package com.fauxx.engine.webview

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.fauxx.data.crawllist.DomainBlocklist
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * Measures what a page can tell about its own visibility inside a pooled phantom WebView (#322
 * item 11). The pool's WebViews are laid out but never attached to a window, and they run in a
 * foreground service with no activity on screen, often with the screen off. A page loading while
 * invisible is the classic mobile ad-fraud signature, so fraud vendors check for it.
 *
 * Each pass loads a probe page from an in-process server on 127.0.0.1 through the real
 * [PhantomWebViewPool] and reports, over a few seconds:
 * - the Page Visibility state (`visibilityState`, `hidden`, `hasFocus()`), prerendering and
 *   discard flags, outer and inner window sizes, and user activation;
 * - how many `requestAnimationFrame` callbacks run, and how soon the first one does;
 * - what IntersectionObserver reports for an element at the top of the page and one far below it,
 *   including `isVisible` from IntersectionObserver v2 where the WebView has it;
 * - timer throttling: how often a 50 ms interval actually fires, and how long a chain of 100
 *   zero-delay timeouts takes;
 * - whether `requestIdleCallback` and a running CSS animation fire, and whether the page records
 *   paint timing (first-contentful-paint, largest-contentful-paint), which real-user-monitoring and
 *   analytics scripts report;
 * - every visibility, focus and lifecycle event the page receives.
 *
 * Passes: the pool's WebView as shipped (detached); the same WebView attached to a visible activity,
 * as a baseline of what a real visible page reports on this WebView; and the same WebView shown in
 * a [Presentation] on a private virtual display the app owns, a candidate fix that needs no
 * activity and no overlay permission. Each runs with the screen on and, apart from the activity,
 * with the screen off. Each pass also logs the app process's CPU time, a rough cost comparison
 * (the renderer process is not counted). With the instrumentation argument `visibilityHoldMs`, two
 * more passes (detached and on the virtual display) hold a page that long with the screen off and
 * report the interval rate over the last minute, to look for Chrome's intensive throttling of pages
 * hidden for over five minutes.
 *
 * Measured on WebView 113 and 153 (#322 step 3): every host reports `visible` with the screen on
 * and off, timers are never throttled (also after 6.5 minutes), and IntersectionObserver, paint
 * timing, `screen.*` and `devicePixelRatio` match the visible baseline. What gives the detached
 * WebView away is rendering: about 17 `requestAnimationFrame` callbacks a second instead of about
 * 60, and CSS animations that never advance. The virtual display matches the baseline on both.
 *
 * Output goes to logcat under [TAG], one `pass.key=value` line per observation. The test asserts
 * only that every pass reported: it is a measurement, run by hand on an emulator or device.
 */
@RunWith(AndroidJUnit4::class)
class VisibilityProbeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun probeWhatHiddenPagesSee() = runBlocking {
        log("webviewPackage", WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" })
        log("sdk", android.os.Build.VERSION.SDK_INT.toString())

        setScreen(on = true)
        runPass("detached.screenOn", Host.DETACHED, holdMs = SHORT_HOLD_MS)
        runPass("attached.screenOn", Host.ACTIVITY, holdMs = SHORT_HOLD_MS)
        runPass("virtualDisplay.screenOn", Host.VIRTUAL_DISPLAY, holdMs = SHORT_HOLD_MS)
        setScreen(on = false)
        runPass("detached.screenOff", Host.DETACHED, holdMs = SHORT_HOLD_MS)
        runPass("virtualDisplay.screenOff", Host.VIRTUAL_DISPLAY, holdMs = SHORT_HOLD_MS)
        InstrumentationRegistry.getArguments().getString(LONG_HOLD_ARG)?.toLongOrNull()?.let { hold ->
            runPass("detached.screenOff.held${hold / 60_000}min", Host.DETACHED, holdMs = hold)
            runPass("virtualDisplay.screenOff.held${hold / 60_000}min", Host.VIRTUAL_DISPLAY, holdMs = hold)
        }
        setScreen(on = true)
    }

    private enum class Host { DETACHED, ACTIVITY, VIRTUAL_DISPLAY }

    private suspend fun runPass(pass: String, host: Host, holdMs: Long) {
        log("$pass.interactive", isInteractive().toString())
        val server = ProbeServer().apply { start() }
        val pool = PhantomWebViewPool(context, mockk<DomainBlocklist>(relaxed = true), PersonaJarStore())
        var scenario: ActivityScenario<ComponentActivity>? = null
        var display: VirtualDisplayHost? = null
        try {
            pool.initialize()
            val webView = pool.acquire()
            try {
                when (host) {
                    Host.DETACHED -> Unit
                    Host.ACTIVITY -> {
                        scenario = ActivityScenario.launch(ComponentActivity::class.java)
                        scenario.onActivity { activity ->
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            activity.setContentView(
                                webView,
                                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                            )
                        }
                    }
                    Host.VIRTUAL_DISPLAY -> {
                        display = VirtualDisplayHost(context)
                        val error = display.show(webView)
                        log("$pass.hostError", error)
                    }
                }
                val cpuBefore = Process.getElapsedCpuTime()
                instrumentation.runOnMainSync {
                    webView.loadUrl("http://127.0.0.1:${server.port}/page?hold=$holdMs", SYNTHETIC_WEBVIEW_HEADERS)
                }
                val reported = server.report.await(holdMs + REPORT_SLACK_MS, TimeUnit.MILLISECONDS)
                log("$pass.appCpuMs", (Process.getElapsedCpuTime() - cpuBefore).toString())
                display?.let { log("$pass.framesRendered", it.frames.toString()) }
                log("$pass.reported", reported.toString())
                server.body?.let { body ->
                    val json = JSONObject(body)
                    json.keys().forEach { key -> log("$pass.$key", json.get(key).toString()) }
                }
                assertTrue("[$pass] the probe page never reported", reported)
            } finally {
                if (host != Host.DETACHED) {
                    instrumentation.runOnMainSync { (webView.parent as? ViewGroup)?.removeView(webView) }
                }
                scenario?.close()
                display?.close()
                pool.release(webView)
            }
        } finally {
            pool.destroy()
            server.stop()
        }
    }

    /**
     * A private virtual display the app owns, rendering into an [ImageReader] whose frames are
     * dropped, with a [Presentation] window on it to hold the WebView. Private presentations need
     * no overlay permission and no activity.
     */
    private inner class VirtualDisplayHost(private val context: Context) {
        private var reader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: Presentation? = null

        @Volatile
        var frames = 0

        /** Shows [webView] on the display; returns the error, or null when it is shown. */
        fun show(webView: WebView): String? {
            var error: String? = null
            instrumentation.runOnMainSync {
                error = runCatching {
                    val metrics = context.resources.displayMetrics
                    val width = webView.width.takeIf { it > 0 } ?: metrics.widthPixels
                    val height = webView.height.takeIf { it > 0 } ?: metrics.heightPixels
                    val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    imageReader.setOnImageAvailableListener({ r ->
                        r.acquireLatestImage()?.close()
                        frames++
                    }, Handler(Looper.getMainLooper()))
                    reader = imageReader
                    val dm = context.getSystemService(DisplayManager::class.java)
                    val vd = dm.createVirtualDisplay("fauxx-probe", width, height, metrics.densityDpi, imageReader.surface, 0)
                    virtualDisplay = vd
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    val p = Presentation(context, vd.display)
                    p.setContentView(webView)
                    p.show()
                    presentation = p
                }.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" }
            }
            return error
        }

        fun close() {
            instrumentation.runOnMainSync {
                runCatching { presentation?.dismiss() }
                runCatching { virtualDisplay?.release() }
                runCatching { reader?.close() }
            }
        }
    }

    private fun isInteractive(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** Wake and unlock, or put the screen to sleep, and wait until the device says it has. */
    private fun setScreen(on: Boolean) {
        if (on) {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        } else {
            shell("input keyevent KEYCODE_SLEEP")
        }
        val deadline = System.currentTimeMillis() + SCREEN_TIMEOUT_MS
        while (isInteractive() != on && System.currentTimeMillis() < deadline) Thread.sleep(200)
        // Give the WebView a moment to hear about the change before the next page loads.
        Thread.sleep(SCREEN_SETTLE_MS)
        log("screen.${if (on) "on" else "off"}", "interactive=${isInteractive()}")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun log(key: String, value: String?) {
        (value ?: "null").chunked(3000).forEachIndexed { i, part ->
            Log.i(TAG, if (i == 0) "$key=$part" else "$key+=$part")
        }
    }

    /** Minimal HTTP/1.1 server: serves the probe page and takes its one report. */
    private class ProbeServer {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val report = CountDownLatch(1)

        @Volatile
        var body: String? = null

        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun start() {
            thread(isDaemon = true, name = "visibility-probe") {
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
            requests += requestLine
            val path = requestLine.split(" ").getOrElse(1) { "/" }
            if (requestLine.startsWith("POST")) {
                val length = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(chars, read, length - read)
                    if (n < 0) break
                    read += n
                }
                body = String(chars, 0, read)
                respond(s, "text/plain", "ok")
                report.countDown()
                return
            }
            if (path.startsWith("/page")) {
                val hold = path.substringAfter("hold=", "3000").takeWhile { it.isDigit() }.toLongOrNull() ?: 3000L
                respond(s, "text/html", pageHtml(hold))
            } else {
                respond(s, "text/plain", "not found", status = "404 Not Found")
            }
        }

        private fun respond(s: Socket, type: String, body: String, status: String = "200 OK") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        }

        private fun pageHtml(holdMs: Long): String =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}" +
                "#top{height:40px;background:#ccc}#spinner{width:20px;height:20px;background:#333;" +
                "animation:spin 100ms linear infinite}#far{margin-top:5000px;height:40px}</style></head>" +
                "<body><div id=\"top\">top</div><div id=\"spinner\"></div><div id=\"far\">far</div>" +
                "<script>var HOLD_MS = $holdMs;\n$PROBE_JS</script></body></html>"
    }

    companion object {
        private const val TAG = "FauxxVisProbe"
        private const val LONG_HOLD_ARG = "visibilityHoldMs"
        private const val SHORT_HOLD_MS = 4_000L
        private const val REPORT_SLACK_MS = 3L * 60 * 1000
        private const val SCREEN_TIMEOUT_MS = 10_000L
        private const val SCREEN_SETTLE_MS = 2_000L

        /** Page-side probe. Plain ES5-ish functions, no template literals, so Kotlin needs no escaping. */
        private val PROBE_JS = """
            (function() {
              var t0 = performance.now(), d0 = Date.now();
              function now() { return Math.round(performance.now() - t0); }
              var r = { events: [] };
              function state() {
                var s = {};
                function tryIt(k, f) { try { s[k] = f(); } catch (e) { s[k] = 'err:' + e; } }
                tryIt('visibilityState', function(){ return document.visibilityState; });
                tryIt('hidden', function(){ return document.hidden; });
                tryIt('hasFocus', function(){ return document.hasFocus(); });
                tryIt('prerendering', function(){ return document.prerendering; });
                tryIt('wasDiscarded', function(){ return document.wasDiscarded; });
                tryIt('outer', function(){ return [outerWidth, outerHeight]; });
                tryIt('inner', function(){ return [innerWidth, innerHeight]; });
                tryIt('screenXY', function(){ return [screenX, screenY]; });
                tryIt('screen', function(){ return [screen.width, screen.height, screen.availWidth, screen.availHeight]; });
                tryIt('dpr', function(){ return devicePixelRatio; });
                tryIt('orientation', function(){ return screen.orientation ? screen.orientation.type : null; });
                tryIt('userActivation', function(){ var u = navigator.userActivation; return u ? [u.hasBeenActive, u.isActive] : null; });
                return s;
              }
              r.start = state();
              ['visibilitychange', 'pagehide', 'pageshow', 'freeze', 'resume', 'focus', 'blur'].forEach(function(name) {
                var record = function() { r.events.push(name + '@' + now() + ':' + document.visibilityState); };
                document.addEventListener(name, record, true);
                window.addEventListener(name, record, true);
              });

              var raf = 0, rafFirst = null;
              function frame() {
                raf++;
                if (rafFirst === null) rafFirst = now();
                if (now() < 2000) requestAnimationFrame(frame);
              }
              requestAnimationFrame(frame);

              var io = [];
              function ioRecord(kind) {
                return function(entries) {
                  entries.forEach(function(e) {
                    io.push({ kind: kind, id: e.target.id, at: now(), isIntersecting: e.isIntersecting,
                      ratio: Math.round(e.intersectionRatio * 100) / 100,
                      isVisible: e.isVisible === undefined ? 'n/a' : e.isVisible,
                      root: e.rootBounds ? [Math.round(e.rootBounds.width), Math.round(e.rootBounds.height)] : null });
                  });
                };
              }
              try {
                var o1 = new IntersectionObserver(ioRecord('v1'));
                o1.observe(document.getElementById('top'));
                o1.observe(document.getElementById('far'));
              } catch (e) { r.ioError = String(e); }
              try {
                var o2 = new IntersectionObserver(ioRecord('v2'), { trackVisibility: true, delay: 100 });
                o2.observe(document.getElementById('top'));
              } catch (e) { r.io2Error = String(e); }

              var ticks = [];
              var interval = setInterval(function(){ ticks.push(performance.now()); }, 50);

              var depth = 0, nestStart = performance.now();
              function nest() {
                depth++;
                if (depth < 100) setTimeout(nest, 0); else r.nestedTimeouts100Ms = Math.round(performance.now() - nestStart);
              }
              setTimeout(nest, 0);

              r.idleCallbacks = 0;
              if (window.requestIdleCallback) requestIdleCallback(function(){ r.idleCallbacks++; r.idleFirstAt = now(); });
              else r.idleCallbacks = 'n/a';

              var lcp = [];
              try {
                new PerformanceObserver(function(list) {
                  list.getEntries().forEach(function(e){ lcp.push(Math.round(e.startTime)); });
                }).observe({ type: 'largest-contentful-paint', buffered: true });
              } catch (e) { r.lcpError = String(e); }

              var animIterations = 0;
              document.getElementById('spinner').addEventListener('animationiteration', function(){ animIterations++; });

              setTimeout(function() {
                clearInterval(interval);
                var spacing = [];
                for (var i = 1; i < ticks.length; i++) spacing.push(ticks[i] - ticks[i - 1]);
                spacing.sort(function(a, b){ return a - b; });
                var lastMinute = ticks.filter(function(t){ return t >= performance.now() - 60000; }).length;
                r.end = state();
                r.heldMs = now();
                r.wallMs = Date.now() - d0;
                r.rafCallbacksIn2s = raf;
                r.rafFirstAt = rafFirst;
                r.intersection = io;
                r.interval50msFired = ticks.length;
                r.interval50msMedianSpacing = spacing.length ? Math.round(spacing[Math.floor(spacing.length / 2)]) : null;
                r.interval50msMaxSpacing = spacing.length ? Math.round(spacing[spacing.length - 1]) : null;
                r.interval50msInLastMinute = lastMinute;
                r.cssAnimationIterations = animIterations;
                try {
                  r.paint = performance.getEntriesByType('paint').map(function(e){ return e.name + '@' + Math.round(e.startTime); });
                } catch (e) { r.paint = 'err:' + e; }
                r.largestContentfulPaint = lcp;
                try { r.documentTimelineMs = Math.round(document.timeline.currentTime); } catch (e) { r.documentTimelineMs = 'err:' + e; }
                fetch('/report', { method: 'POST', body: JSON.stringify(r) });
              }, HOLD_MS);
            })();
        """.trimIndent()
    }
}
