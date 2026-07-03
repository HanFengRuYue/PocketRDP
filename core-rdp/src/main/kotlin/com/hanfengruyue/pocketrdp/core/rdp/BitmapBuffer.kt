package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Double-buffered frame surface between the native FreeRDP worker thread and the renderer.
 *
 * **Why double-buffer (the "逐行扫描"/tearing fix):** the native worker copies decoded pixels into
 * a Bitmap (via `LibFreeRDP.updateGraphics`) while the UI thread blits that same Bitmap to the
 * screen on a free-running render loop ([com.hanfengruyue.pocketrdp.feature.session.render.RdpSurface]).
 * Drawing a Bitmap on a hardware-accelerated Canvas schedules an async texture upload that reads the
 * Bitmap's pixels *later* on the RenderThread — so if the worker is mid-way through a large region
 * copy, the upload captures a half-written frame and the user sees a progressive-scan tear during big
 * repaints (small updates finish within one frame so they looked fine). The fix is a classic
 * front/back swap: the worker writes the **back** buffer, and only once a frame is complete is it
 * swapped to the **front** buffer that the UI draws — so the UI never reads a buffer being written.
 *
 * - [nativeBuffer] is the back buffer the worker writes into.
 * - [peekFront] returns the front buffer the UI draws (a complete, stable frame).
 * - [commitFrame] swaps back→front after the worker finishes a region update.
 * - `current` exposes whether a buffer exists at all (drives the "waiting for frame" placeholder);
 *   it is updated on resize/release only, NOT per frame, so the session canvas does not recompose
 *   at the content rate.
 *
 * Memory cost is two same-size bitmaps (~2×8 MB at 1080p). Like the previous single-buffer
 * implementation, old bitmaps are never `recycle()`d — the UI thread may still hold a reference for
 * a frame or two; let GC reclaim them (a recycle-then-draw race previously caused SIGSEGV).
 */
class BitmapBuffer {

    private val lock = Any()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    // Region updated in the previously-published frame. The back buffer is exactly one published
    // generation behind (that update went to the other buffer), so it must be re-synced over this
    // rect before the next swap or the published frame would be missing it.
    private var lastRect: Rect? = null

    // Monotonic publish sequence + the uptime each front frame was committed. The renderer reads these
    // (peekFrontSeq / peekFrontCommitMs) to measure decode→present latency: when onDraw sees the seq
    // advance, (now − commitMs) is how long that frame waited between decode-done and being drawn. Reads
    // are racy-but-cheap (a metric) — a swap mid-read at worst attributes a slightly newer commit time.
    @Volatile private var frontSeq: Long = 0L
    @Volatile private var frontCommitMs: Long = 0L

    private val _current = MutableStateFlow<Bitmap?>(null)
    /** Non-null once a framebuffer has been allocated. Identity is NOT meaningful per frame — used
     *  only to flip the "waiting for remote frame" placeholder. */
    val current: StateFlow<Bitmap?> = _current.asStateFlow()

    private val _hasPublishedFrame = MutableStateFlow(false)
    val hasPublishedFrame: StateFlow<Boolean> = _hasPublishedFrame.asStateFlow()

    private val _dirty = MutableSharedFlow<Rect>(extraBufferCapacity = 256)
    val dirty: SharedFlow<Rect> = _dirty.asSharedFlow()

    /** Back buffer the native worker writes pixel updates into. Null until [resize]. */
    fun nativeBuffer(): Bitmap? = synchronized(lock) { back }

    /** Region updated in the previous published frame; the back buffer must be re-synced over it
     *  before publishing so the swapped-in frame is a complete mirror of the gdi framebuffer. */
    fun staleRect(): Rect? = synchronized(lock) { lastRect }

    /** Front buffer the UI draws — a complete, stable frame. Drawn outside the lock; safe because
     *  the worker only ever writes the back buffer (see class doc). */
    fun peekFront(): Bitmap? = synchronized(lock) { front }

    /** Monotonic sequence of the current front frame; advances on every [commitFrame]. The renderer
     *  uses a change in this value to detect a freshly-published frame (decode→present timing). */
    fun peekFrontSeq(): Long = frontSeq

    /** Uptime (ms) the current front frame was committed — paired with [peekFrontSeq] for present timing. */
    fun peekFrontCommitMs(): Long = frontCommitMs

    /**
     * Thread-safe, downscaled COPY of the current front frame — used for the per-connection desktop
     * thumbnail shown on the connection list (issue: 读取被控电脑桌面图片). Returns a brand-new,
     * independent bitmap (so the caller can encode it off-thread without touching our buffers) or
     * null when no frame has been published yet.
     *
     * The whole copy runs inside [lock] so it can never race the worker's [commitFrame] swap: while
     * we hold the lock the front buffer is stable. [Bitmap.createScaledBitmap] reads the source and
     * allocates a fresh result, so once it returns the lock can drop and the worker resumes. The
     * longest dimension is clamped to [maxDimension]; aspect ratio is preserved and we never upscale.
     */
    fun snapshot(maxDimension: Int): Bitmap? = synchronized(lock) {
        val src = front ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || maxDimension <= 0) return null
        val scale = (maxDimension.toFloat() / maxOf(w, h)).coerceAtMost(1f)
        val tw = (w * scale).roundToInt().coerceAtLeast(1)
        val th = (h * scale).roundToInt().coerceAtLeast(1)
        runCatching { Bitmap.createScaledBitmap(src, tw, th, true) }.getOrNull()
    }

    fun resize(width: Int, height: Int): Bitmap {
        synchronized(lock) {
            val f = front
            if (f != null && f.width == width && f.height == height) return f
        }
        // Allocate outside the lock (two ~8 MB bitmaps) so the UI's peekFront isn't blocked on it;
        // resize is only ever called from the single native worker thread, so there's no concurrent
        // resize to race the swap below.
        val newFront = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val newBack = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        synchronized(lock) {
            front = newFront
            back = newBack
            lastRect = null
        }
        _current.value = newFront
        _hasPublishedFrame.value = false
        // Deliberately do NOT recycle the old bitmaps (see class doc) — let GC reclaim them.
        return newFront
    }

    /**
     * Publish the back buffer after the worker has written [x,y,w,h] (and re-synced [staleRect])
     * into it. Swaps back→front so the UI picks up a complete frame on its next render tick.
     */
    fun commitFrame(x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            val tmp = front
            front = back
            back = tmp
            lastRect = Rect(x, y, x + w, y + h)
            frontSeq++
            frontCommitMs = SystemClock.uptimeMillis()
        }
        _hasPublishedFrame.value = true
        _dirty.tryEmit(Rect(x, y, x + w, y + h))
    }

    fun notifyDirty(x: Int, y: Int, w: Int, h: Int) {
        _dirty.tryEmit(Rect(x, y, x + w, y + h))
    }

    fun release() {
        synchronized(lock) {
            front = null
            back = null
            lastRect = null
        }
        _current.value = null
        _hasPublishedFrame.value = false
    }
}
