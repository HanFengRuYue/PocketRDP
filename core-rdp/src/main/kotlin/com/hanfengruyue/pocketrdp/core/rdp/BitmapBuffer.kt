package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * - [hasPublishedFrame] flips true once the first complete frame has been swapped to front and
 *   resets on resize/release so snapshots and overlays never surface an uninitialized black frame.
 *
 * Memory cost is two same-size bitmaps (~2×8 MB at 1080p). Like the previous single-buffer
 * implementation, old bitmaps are never `recycle()`d — the UI thread may still hold a reference for
 * a frame or two; let GC reclaim them (a recycle-then-draw race previously caused SIGSEGV).
 */
class BitmapBuffer {
    private val frames = DoubleBufferedFrames<Bitmap, Rect>(
        allocate = { width, height -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) },
        scaleCopy = { src, width, height ->
            runCatching { Bitmap.createScaledBitmap(src, width, height, true) }.getOrNull()
        },
        widthOf = Bitmap::getWidth,
        heightOf = Bitmap::getHeight,
        makeRect = ::Rect,
        nowMillis = SystemClock::uptimeMillis,
    )

    val hasPublishedFrame: StateFlow<Boolean> = frames.hasPublishedFrame

    /** Back buffer the native worker writes pixel updates into. Null until [resize]. */
    fun nativeBuffer(): Bitmap? = frames.nativeBuffer()

    /** Region updated in the previous published frame; the back buffer must be re-synced over it
     *  before publishing so the swapped-in frame is a complete mirror of the gdi framebuffer. */
    fun staleRect(): Rect? = frames.staleRect()

    /** Front buffer the UI draws — a complete, stable frame. Drawn outside the lock; safe because
     *  the worker only ever writes the back buffer (see class doc). */
    fun peekFront(): Bitmap? = frames.peekFront()

    /** Monotonic sequence of the current front frame; advances on every [commitFrame]. The renderer
     *  uses a change in this value to detect a freshly-published frame (decode→present timing). */
    fun peekFrontSeq(): Long = frames.peekFrontSeq()

    /** Uptime (ms) the current front frame was committed — paired with [peekFrontSeq] for present timing. */
    fun peekFrontCommitMs(): Long = frames.peekFrontCommitMs()

    /**
     * Thread-safe, downscaled COPY of the current front frame — used for the per-connection desktop
     * thumbnail shown on the connection list (issue: 读取被控电脑桌面图片). Returns a brand-new,
     * independent bitmap (so the caller can encode it off-thread without touching our buffers) or
     * null when no frame has been published yet.
     *
     * The whole copy runs inside the buffer lock so it can never race the worker's [commitFrame]
     * swap: while we hold the lock the front buffer is stable. [Bitmap.createScaledBitmap] reads
     * the source and allocates a fresh result, so once it returns the lock can drop and the worker
     * resumes. The longest dimension is clamped to [maxDimension]; aspect ratio is preserved and
     * we never upscale.
     */
    fun snapshot(maxDimension: Int): Bitmap? = frames.snapshot(maxDimension)

    fun resize(width: Int, height: Int): Bitmap = frames.resize(width, height)

    /**
     * Publish the back buffer after the worker has written [x,y,w,h] (and re-synced [staleRect])
     * into it. Swaps back→front so the UI picks up a complete frame on its next render tick.
     */
    fun commitFrame(x: Int, y: Int, w: Int, h: Int) = frames.commitFrame(x, y, w, h)

    /**
     * Copy [x,y,w,h] (plus the previous stale rect when not covered) into the back buffer and
     * commit the completed frame in one allocation-free path.
     */
    internal fun updateBackBuffer(
        inst: Long,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        commitTimeMs: Long,
        copyRegion: (Long, Bitmap, Int, Int, Int, Int) -> Boolean,
    ): Boolean = frames.updateBackBuffer(inst, x, y, w, h, commitTimeMs, copyRegion)

    fun release() = frames.release()
}

internal class DoubleBufferedFrames<F : Any, R : Any>(
    private val allocate: (width: Int, height: Int) -> F,
    private val scaleCopy: (source: F, width: Int, height: Int) -> F?,
    private val widthOf: (F) -> Int,
    private val heightOf: (F) -> Int,
    private val makeRect: (left: Int, top: Int, right: Int, bottom: Int) -> R,
    private val nowMillis: () -> Long,
) {
    private val lock = Any()
    @Volatile private var front: F? = null
    private var back: F? = null
    // Region updated in the previously-published frame, stored as primitive bounds to avoid
    // per-frame Rect allocations. The back buffer is one published generation behind, so it must
    // be re-synced over this region before the next swap unless the new update covers it.
    private var hasStaleRect: Boolean = false
    private var staleLeft: Int = 0
    private var staleTop: Int = 0
    private var staleRight: Int = 0
    private var staleBottom: Int = 0

    // Monotonic publish sequence + the uptime each front frame was committed.
    @Volatile private var frontSeq: Long = 0L
    @Volatile private var frontCommitMs: Long = 0L

    private val _hasPublishedFrame = MutableStateFlow(false)
    val hasPublishedFrame: StateFlow<Boolean> = _hasPublishedFrame.asStateFlow()

    fun nativeBuffer(): F? = synchronized(lock) { back }

    fun staleRect(): R? = synchronized(lock) {
        if (hasStaleRect) makeRect(staleLeft, staleTop, staleRight, staleBottom) else null
    }

    /** Lock-free read so Main-thread rendering never stalls behind off-thread [snapshot] scaling. */
    fun peekFront(): F? = front

    fun peekFrontSeq(): Long = frontSeq

    fun peekFrontCommitMs(): Long = frontCommitMs

    fun snapshot(maxDimension: Int): F? = synchronized(lock) {
        if (!_hasPublishedFrame.value) return null
        val src = front ?: return null
        val w = widthOf(src)
        val h = heightOf(src)
        if (w <= 0 || h <= 0 || maxDimension <= 0) return null
        val scale = (maxDimension.toFloat() / maxOf(w, h)).coerceAtMost(1f)
        val tw = (w * scale).roundToInt().coerceAtLeast(1)
        val th = (h * scale).roundToInt().coerceAtLeast(1)
        scaleCopy(src, tw, th)
    }

    fun resize(width: Int, height: Int): F {
        synchronized(lock) {
            val f = front
            if (f != null && widthOf(f) == width && heightOf(f) == height) return f
        }
        // Allocate outside the lock so the UI's peekFront isn't blocked on large allocations.
        val newFront = allocate(width, height)
        val newBack = allocate(width, height)
        synchronized(lock) {
            front = newFront
            back = newBack
            hasStaleRect = false
            _hasPublishedFrame.value = false
        }
        // Deliberately do NOT recycle old frames — let GC reclaim them after RenderThread finishes.
        return newFront
    }

    fun commitFrame(x: Int, y: Int, w: Int, h: Int, commitTimeMs: Long = nowMillis()) {
        synchronized(lock) {
            val tmp = front
            val nextFront = back
            front = nextFront
            back = tmp
            if (nextFront != null) {
                hasStaleRect = true
                staleLeft = x
                staleTop = y
                staleRight = x + w
                staleBottom = y + h
            } else {
                hasStaleRect = false
            }
            // Write frontCommitMs before incrementing frontSeq so readers observing a new
            // sequence number see the matching front buffer and commit timestamp.
            frontCommitMs = commitTimeMs
            frontSeq++
            _hasPublishedFrame.value = nextFront != null
        }
    }

    fun updateBackBuffer(
        token: Long,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        commitTimeMs: Long = nowMillis(),
        copyRegion: (Long, F, Int, Int, Int, Int) -> Boolean,
    ): Boolean {
        val target: F
        val needStale: Boolean
        val sLeft: Int
        val sTop: Int
        val sWidth: Int
        val sHeight: Int
        synchronized(lock) {
            target = back ?: return false
            if (hasStaleRect) {
                sLeft = staleLeft
                sTop = staleTop
                sWidth = staleRight - sLeft
                sHeight = staleBottom - sTop
                val isEmpty = sWidth <= 0 || sHeight <= 0
                val coversOrigin = x <= sLeft && y <= sTop
                val coversExtent = x.toLong() + w >= staleRight.toLong() &&
                    y.toLong() + h >= staleBottom.toLong()
                needStale = !isEmpty && !(coversOrigin && coversExtent)
            } else {
                sLeft = 0
                sTop = 0
                sWidth = 0
                sHeight = 0
                needStale = false
            }
        }
        val copied = copyRegion(token, target, x, y, w, h) &&
            (!needStale || copyRegion(token, target, sLeft, sTop, sWidth, sHeight))
        if (copied) {
            commitFrame(x, y, w, h, commitTimeMs)
        }
        return copied
    }

    fun release() {
        synchronized(lock) {
            front = null
            back = null
            hasStaleRect = false
            _hasPublishedFrame.value = false
        }
    }
}
