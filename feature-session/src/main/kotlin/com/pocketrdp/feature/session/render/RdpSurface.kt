package com.pocketrdp.feature.session.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Backing canvas for the RDP framebuffer. Native draw thread writes into a Bitmap; we blit
 * dirty regions to the Surface on a Choreographer beat to coalesce high-frequency updates.
 *
 * The view itself does NOT consume touch events — Compose handles all gestures via
 * Modifier.pointerInput on the AndroidView wrapper. See feature-session/SessionScreen.
 */
class RdpSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val dirtyAccumulator: Rect = Rect()
    private val hasDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lock = Any()

    private var backing: Bitmap? = null
    private val viewportMatrix: Matrix = Matrix()
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var hasSurface: Boolean = false
    private var scheduled: Boolean = false

    init {
        holder.addCallback(this)
        isFocusable = false
        isClickable = false
        setBackgroundColor(Color.BLACK)
    }

    fun setBacking(bitmap: Bitmap?) {
        synchronized(lock) {
            backing = bitmap
            if (bitmap != null) {
                dirtyAccumulator.set(0, 0, bitmap.width, bitmap.height)
                hasDirty.set(true)
                scheduleDraw()
            }
        }
    }

    fun applyViewport(matrix: Matrix) {
        viewportMatrix.set(matrix)
        invalidateAll()
    }

    /** Merge a dirty rect from the native thread and schedule a frame on the UI thread. */
    fun markDirty(rect: Rect) {
        synchronized(lock) {
            if (hasDirty.get()) dirtyAccumulator.union(rect) else dirtyAccumulator.set(rect)
            hasDirty.set(true)
        }
        scheduleDraw()
    }

    private fun invalidateAll() {
        val bm = backing ?: return
        synchronized(lock) {
            dirtyAccumulator.set(0, 0, bm.width, bm.height)
            hasDirty.set(true)
        }
        scheduleDraw()
    }

    private fun scheduleDraw() {
        if (!hasSurface) return
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!hasSurface || !hasDirty.get()) return

        val bm = backing ?: return
        val region: Rect
        synchronized(lock) {
            region = Rect(dirtyAccumulator)
            dirtyAccumulator.setEmpty()
            hasDirty.set(false)
        }

        val canvas: Canvas? = holder.lockCanvas(/* dirty = */ null)
        if (canvas != null) {
            try {
                canvas.drawColor(Color.BLACK)
                canvas.save()
                canvas.concat(viewportMatrix)
                canvas.drawBitmap(bm, 0f, 0f, paint)
                canvas.restore()
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        // Avoid suppressing region var — kept for future partial blit optimisation.
        @Suppress("UNUSED_VARIABLE") val unused = region
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        hasSurface = true
        invalidateAll()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        invalidateAll()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
    }
}
