package com.fauxx.engine.webview

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import timber.log.Timber

/**
 * Somewhere a pooled WebView can be drawn as if it were on screen (#322 item 11).
 *
 * A WebView that is laid out but never attached to a window gets about 17 animation frames a
 * second instead of about 60, and its CSS animations never advance (measured on WebView 113 and
 * 153 by VisibilityProbeInstrumentedTest), so a page that times frames or watches an animation can
 * tell nobody is looking. Attached to a window, it renders like a page on screen.
 *
 * A WebView is never taken back off a host for further use: once attached, a WebView that leaves
 * its window reports its page as hidden and draws no frames at all, a sharper tell than never
 * being attached (measured on WebView 153), and nothing public restores the never-attached state.
 * The pool replaces such a WebView with a fresh one instead ([OnscreenPlacement]).
 *
 * All calls on the main thread.
 */
interface OnscreenHost {
    /** Draw [webView] here, taking it from wherever it was. Throws if the host cannot be set up. */
    fun show(webView: WebView)

    /** Stop drawing [webView], if it is the one shown, before it is destroyed. */
    fun hide(webView: WebView)

    /** Release everything the host holds. It is not used again. */
    fun destroy()
}

/** Makes one [OnscreenHost] per pool slot; [width] by [height] pixels, like the slot's viewport. */
fun interface OnscreenHostFactory {
    fun create(width: Int, height: Int): OnscreenHost

    companion object {
        /** [VirtualDisplayHost]s on [context]. */
        fun virtualDisplays(context: Context) = OnscreenHostFactory { w, h -> VirtualDisplayHost(context, w, h) }

        /** No host: every page stays detached. For tests that build the pool directly. */
        val NONE = OnscreenHostFactory { _, _ -> throw UnsupportedOperationException("no on-screen host") }
    }
}

/**
 * A private virtual display the app owns, with a [Presentation] window on it holding the WebView.
 *
 * Private virtual displays and the presentations on them need no permission and no activity, so
 * this works from the engine's foreground service, with the screen on or off. The display renders
 * into an [ImageReader] whose frames are dropped at once: nothing is captured or kept. The display
 * has the slot's viewport size and the real screen's density, so `innerWidth`, `screen.*` and
 * `devicePixelRatio` read the same as before. The reader is only [OUTPUT_SIZE] pixels square: the
 * display's composition is scaled into it, which keeps the cost of the buffers small without
 * changing how the page itself renders.
 */
class VirtualDisplayHost(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val outputSize: Int = OUTPUT_SIZE,
) : OnscreenHost {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var shown: WebView? = null

    override fun show(webView: WebView) {
        if (shown === webView && webView.parent != null) return
        val window = presentation ?: open()
        (webView.parent as? ViewGroup)?.removeView(webView)
        window.setContentView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        shown = webView
    }

    override fun hide(webView: WebView) {
        if (shown !== webView) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        shown = null
    }

    override fun destroy() {
        shown?.let { (it.parent as? ViewGroup)?.removeView(it) }
        shown = null
        runCatching { presentation?.dismiss() }.onFailure { Timber.w(it, "Could not dismiss the page display") }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        presentation = null
        display = null
        reader = null
    }

    private fun open(): Presentation {
        try {
            val imageReader = ImageReader.newInstance(outputSize, outputSize, PixelFormat.RGBA_8888, 2)
            imageReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
            reader = imageReader
            val manager = context.getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("no DisplayManager")
            // Flags 0: private to this app, shown to nothing else, and needing no permission.
            val virtualDisplay = manager.createVirtualDisplay(
                DISPLAY_NAME, width, height, context.resources.displayMetrics.densityDpi, imageReader.surface, 0,
            ) ?: throw IllegalStateException("virtual display not created")
            display = virtualDisplay
            return Presentation(context, virtualDisplay.display).also {
                it.show()
                presentation = it
            }
        } catch (e: Exception) {
            destroy()
            throw e
        }
    }

    private companion object {
        const val DISPLAY_NAME = "phantom-page"

        /** Side of the square buffer the display's frames land in, and are dropped from. */
        const val OUTPUT_SIZE = 16
    }
}

/**
 * Keeps track of which pool slot is drawn where (#322 item 11). One [OnscreenHost] per slot tag,
 * made on first use and kept for the pool's life, so the fresh WebView that replaces another in
 * its slot reuses the display. If a host cannot be set up on this device, the pool stops trying
 * for the rest of the process and every page stays detached, as before.
 *
 * Main thread only.
 */
internal class OnscreenPlacement(private val factory: OnscreenHostFactory) {
    private val hosts = HashMap<String, OnscreenHost>()
    private val shown = HashSet<WebView>()
    private var unavailable = false

    /** Draw [webView] as if on screen, on a display of [width] by [height] pixels. */
    fun show(webView: WebView, width: Int, height: Int) {
        val tag = webView.tag as? String ?: return
        if (unavailable || width <= 0 || height <= 0 || webView in shown) return
        try {
            hosts.getOrPut(tag) { factory.create(width, height) }.show(webView)
            shown += webView
        } catch (e: Exception) {
            unavailable = true
            hosts.remove(tag)?.let { runCatching { it.destroy() } }
            Timber.w(e, "Pages cannot be drawn as if on screen on this device; they stay detached")
        }
    }

    /** Whether [webView] has been drawn on a host: it cannot then be used detached again. */
    fun isShown(webView: WebView): Boolean = webView in shown

    /** Take [webView] off its host before it is destroyed. */
    fun forget(webView: WebView) {
        if (!shown.remove(webView)) return
        val tag = webView.tag as? String ?: return
        hosts[tag]?.let { runCatching { it.hide(webView) } }
    }

    fun destroyAll() {
        hosts.values.forEach { runCatching { it.destroy() } }
        hosts.clear()
        shown.clear()
    }
}
