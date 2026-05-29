package com.hanfengruyue.pocketrdp.feature.session.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Backing canvas for the RDP framebuffer.
 *
 * History: this used to be SurfaceView + Choreographer.postFrameCallback + holder.lockCanvas.
 * That stack is too fragile for cross-thread invalidation — Choreographer.getInstance()
 * requires the caller thread to have a Looper, and any drop in the chain (markDirty arriving
 * before surfaceCreated, post() racing against view-attach state, separate-layer z-order
 * inside Compose Box) leaves the screen showing whatever was last blitted (i.e. the freshly
 * allocated zeroed Bitmap → solid black).
 *
 * The current implementation is a plain View that draws the bitmap inside its onDraw, which
 * re-schedules itself continuously (see scheduleNextFrame) so the framebuffer is re-blitted at a
 * steady rate — either VSync-paced (targetFrameRate == 0) or throttled to a fixed target fps.
 * Because that self-loop always re-blits the bitmap's current pixels, [markDirty] no longer needs
 * to postInvalidate; driving redraws purely from the loop is what keeps the displayed frame rate
 * fixed instead of tracking the bursty content-update rate. Compose's AndroidView wrapper draws
 * this view inline with the rest of the tree, so there is no surface layer to fight with.
 *
 * The view itself does NOT consume touch events — Compose handles gestures via
 * Modifier.pointerInput on the AndroidView wrapper. See feature-session/SessionScreen.
 */
class RdpSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val lock = Any()
    private var backing: Bitmap? = null
    private val viewportMatrix: Matrix = Matrix()
    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Target render frame rate (fps). 0 = uncapped → follow VSync (the historical behaviour);
     * > 0 = throttle the self-redraw loop to this rate, further clamped to the device screen
     * refresh rate. Written from the Compose update lambda (UI thread), read in onDraw.
     */
    @Volatile
    var targetFrameRate: Int = 0

    /** Invoked once per actually-rendered frame (UI thread). Drives the FPS counter. */
    var onFrameRendered: (() -> Unit)? = null

    /** uptimeMillis of the current frame's start; scheduleNextFrame paces the next frame from it. */
    @Volatile
    private var lastFrameAtMs: Long = 0L

    init {
        isFocusable = false
        isClickable = false
        setBackgroundColor(Color.BLACK)
    }

    fun setBacking(bitmap: Bitmap?) {
        synchronized(lock) { backing = bitmap }
        postInvalidate()
    }

    fun applyViewport(matrix: Matrix) {
        synchronized(lock) { viewportMatrix.set(matrix) }
        postInvalidate()
    }

    /**
     * Native draw thread signals a region changed. We intentionally do NOT postInvalidate here:
     * the onDraw self-loop already re-blits the backing bitmap at the (possibly throttled) target
     * frame rate, and drawBitmap reads the bitmap's current pixels every frame, so a pixel change
     * shows up on the next scheduled frame (≤ 1/fps later). An immediate invalidate would bypass
     * the throttle and let a fast content rate push the render rate — and the shown FPS — back
     * above the configured target.
     */
    @Suppress("UNUSED_PARAMETER")
    fun markDirty(rect: Rect) {
        // no-op: redraws are driven by the onDraw self-loop (see scheduleNextFrame).
    }

    override fun onDraw(canvas: Canvas) {
        // Stamp the frame-start time first so scheduleNextFrame paces the *next* frame from here,
        // keeping the cadence exactly 1/fps regardless of how long this draw takes.
        lastFrameAtMs = SystemClock.uptimeMillis()
        val bm: Bitmap?
        val matrix: Matrix
        synchronized(lock) {
            bm = backing
            matrix = Matrix(viewportMatrix)
        }
        if (bm != null) {
            if (matrix.isIdentity) {
                val viewW = width.toFloat()
                val viewH = height.toFloat()
                val bmW = bm.width.toFloat()
                val bmH = bm.height.toFloat()
                if (viewW > 0f && viewH > 0f && bmW > 0f && bmH > 0f) {
                    // Default viewport: fit-to-view, preserve aspect ratio, centre. Without
                    // this a 1920x1080 desktop is blitted 1:1 from the top-left and most of
                    // it lands off-screen on a phone.
                    val scale = minOf(viewW / bmW, viewH / bmH)
                    val dx = (viewW - bmW * scale) / 2f
                    val dy = (viewH - bmH * scale) / 2f
                    canvas.save()
                    canvas.translate(dx, dy)
                    canvas.scale(scale, scale)
                    canvas.drawBitmap(bm, 0f, 0f, paint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bm, 0f, 0f, paint)
                }
            } else {
                canvas.save()
                canvas.concat(matrix)
                canvas.drawBitmap(bm, 0f, 0f, paint)
                canvas.restore()
            }
        }
        // One actually-rendered frame — drives the FPS counter (render rate, not content rate).
        onFrameRendered?.invoke()
        // Belt-and-braces continuous redraw: always re-schedule the next frame as long as we are
        // attached. This self-loop must NEVER stop while attached — it is the load-bearing guard
        // against the historical "connected but black screen" bug (markDirty can't be relied on to
        // reach us from every codepath in time). Throttling only DELAYS the next frame to honour
        // targetFrameRate; it never cancels it.
        if (isAttachedToWindow) scheduleNextFrame()
    }

    /** Effective fps: 0 = uncapped (follow VSync); otherwise target clamped to screen refresh. */
    private fun effectiveFps(): Int {
        if (targetFrameRate <= 0) return 0
        val screen = display?.refreshRate?.toInt()?.takeIf { it > 0 } ?: DEFAULT_REFRESH_HZ
        return minOf(targetFrameRate, screen)
    }

    private fun scheduleNextFrame() {
        val fps = effectiveFps()
        if (fps <= 0) {
            postInvalidateOnAnimation() // uncapped: pace with VSync (≈ screen refresh)
            return
        }
        val intervalMs = MILLIS_PER_SECOND / fps
        val elapsed = SystemClock.uptimeMillis() - lastFrameAtMs
        if (elapsed >= intervalMs) {
            postInvalidateOnAnimation()
        } else {
            postInvalidateDelayed(intervalMs - elapsed)
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private const val DEFAULT_REFRESH_HZ = 60
    }
}
