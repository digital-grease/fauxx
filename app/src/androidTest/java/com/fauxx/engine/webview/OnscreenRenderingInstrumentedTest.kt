package com.fauxx.engine.webview

import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.model.PageRendering
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The page-rendering setting (#322 item 11) on a real WebView: with a page drawn as if on screen,
 * it gets full-rate animation frames and its CSS animations run; detached, it gets fewer frames
 * and its animations never advance (see VisibilityProbeInstrumentedTest for the full
 * measurement). Covers every mode, drawing with the screen off, and that a WebView once drawn is
 * never handed out detached: taken off its display, it would report its page hidden.
 */
@RunWith(AndroidJUnit4::class)
class OnscreenRenderingInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = RenderServer().apply { start() }

    @After
    fun tearDown() {
        server.stop()
        setScreen(on = true)
    }

    @Test
    fun allPagesAreDrawnLikeAPageOnScreen() = runBlocking {
        withPool(PageRendering.ALL_PAGES) { pool, _ ->
            val webView = pool.acquire()
            assertDrawn("all pages", measure(webView))
            setScreen(on = false)
            assertDrawn("all pages, screen off", measure(webView))
            pool.release(webView)
        }
    }

    @Test
    fun offLeavesPagesDetached() = runBlocking {
        withPool(PageRendering.OFF) { pool, _ ->
            val webView = pool.acquire()
            assertDetached("off", measure(webView))
            pool.release(webView)
        }
    }

    @Test
    fun searchOnlyDrawsSearchesAndLeavesOtherPagesDetached() = runBlocking {
        withPool(PageRendering.SEARCH_ONLY) { pool, _ ->
            val search = pool.acquire()
            assertDetached("search only, before the search starts", measure(search))
            pool.startingSearch(search)
            assertDrawn("search only, during the search", measure(search))
            pool.release(search)
            // The next action, of any module, gets a WebView that was never drawn: one taken off
            // its display would report the page hidden.
            repeat(2) {
                val next = pool.acquire()
                assertDetached("search only, the next action", measure(next))
                pool.release(next)
            }
        }
    }

    @Test
    fun turningDrawingOffWhileRunningLeavesNoPageHidden() = runBlocking {
        withPool(PageRendering.ALL_PAGES) { pool, setMode ->
            repeat(2) {
                val drawn = pool.acquire()
                assertDrawn("all pages", measure(drawn))
                pool.release(drawn)
            }
            setMode(PageRendering.OFF)
            repeat(2) {
                val detached = pool.acquire()
                assertDetached("after switching off", measure(detached))
                pool.release(detached)
            }
        }
    }

    private suspend fun withPool(
        initial: PageRendering,
        block: suspend (PhantomWebViewPool, setMode: (PageRendering) -> Unit) -> Unit,
    ) {
        var mode = initial
        val prefs = object : PhantomBrowsingPrefs {
            override fun loadImages(): Boolean = false
            override fun pageRendering(): PageRendering = mode
        }
        val pool = PhantomWebViewPool(
            context, mockk<DomainBlocklist>(relaxed = true), PersonaJarStore(), PhantomIdentityProvider.NONE, prefs,
            onscreenHosts = OnscreenHostFactory.virtualDisplays(context),
        )
        try {
            pool.initialize()
            block(pool) { mode = it }
        } finally {
            pool.destroy()
        }
    }

    /** Load the probe page and return its report. */
    private fun measure(webView: WebView): JSONObject {
        server.reports.clear()
        instrumentation.runOnMainSync {
            webView.loadUrl("http://127.0.0.1:${server.port}/page", SYNTHETIC_WEBVIEW_HEADERS)
        }
        val body = server.reports.poll(30, TimeUnit.SECONDS)
        assertTrue("the probe page never reported", body != null)
        return JSONObject(body!!).also { Log.i(TAG, it.toString()) }
    }

    private fun assertDrawn(what: String, r: JSONObject) {
        assertTrue("[$what] animation frames in 2 s: ${r.getInt("frames")}", r.getInt("frames") >= 80)
        assertTrue("[$what] CSS animation iterations: ${r.getInt("iterations")}", r.getInt("iterations") >= 15)
        assertEquals("[$what] visibility", "visible", r.getString("visibility"))
    }

    /**
     * Detached: CSS animations do not run, while the page still reads itself as visible and still
     * gets frames. The detached frame rate varies (17-30 a second measured), so it is not pinned. A
     * WebView taken off a display used to report its page hidden with no frames at all.
     */
    private fun assertDetached(what: String, r: JSONObject) {
        assertTrue("[$what] animation frames in 2 s: ${r.getInt("frames")}", r.getInt("frames") > 0)
        assertEquals("[$what] CSS animation iterations", 0, r.getInt("iterations"))
        assertEquals("[$what] visibility", "visible", r.getString("visibility"))
    }

    private fun setScreen(on: Boolean) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive == on) return
        val command = if (on) "input keyevent KEYCODE_WAKEUP" else "input keyevent KEYCODE_SLEEP"
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (power.isInteractive != on && System.currentTimeMillis() < deadline) Thread.sleep(200)
        Thread.sleep(1_000)
    }

    /** Serves the probe page and queues each report it posts. */
    private class RenderServer {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val reports = LinkedBlockingQueue<String>()

        fun start() {
            thread(isDaemon = true, name = "render-probe") {
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
                respond(s, "text/plain", "ok")
                reports.add(String(chars, 0, read))
                return
            }
            respond(s, "text/html", PAGE)
        }

        private fun respond(s: Socket, type: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        }
    }

    private companion object {
        const val TAG = "FauxxRenderProbe"

        /** Counts animation frames over 2 s and CSS animation iterations over 3 s, then reports. */
        val PAGE = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}
            #s{width:20px;height:20px;background:#333;animation:spin 100ms linear infinite}</style></head>
            <body><div id="s"></div><script>
            var t0 = performance.now(), frames = 0, iterations = 0;
            function f() { frames++; if (performance.now() - t0 < 2000) requestAnimationFrame(f); }
            requestAnimationFrame(f);
            document.getElementById('s').addEventListener('animationiteration', function(){ iterations++; });
            setTimeout(function() {
              fetch('/report', { method: 'POST', body: JSON.stringify({ frames: frames, iterations: iterations,
                visibility: document.visibilityState }) });
            }, 3000);
            </script></body></html>
        """.trimIndent()
    }
}
