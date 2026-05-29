package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared frame surface between native draw thread and the renderer.
 *
 * - `current` holds the active ARGB_8888 Bitmap that JNI writes into.
 * - `dirty` carries rectangular regions that need to be re-blit to the visible Surface.
 *
 * RdpSurface (UI thread) subscribes; RdpClient (native callback thread) emits.
 */
class BitmapBuffer {

    private val _current = MutableStateFlow<Bitmap?>(null)
    val current: StateFlow<Bitmap?> = _current.asStateFlow()

    private val _dirty = MutableSharedFlow<Rect>(extraBufferCapacity = 256)
    val dirty: SharedFlow<Rect> = _dirty.asSharedFlow()

    @Synchronized
    fun resize(width: Int, height: Int): Bitmap {
        val old = _current.value
        if (old != null && old.width == width && old.height == height) return old
        val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        _current.value = fresh
        // Deliberately do NOT recycle the old bitmap here. Native FreeRDP thread runs resize()
        // (e.g. when the server emits a desktop-resize on rotation), but RdpSurface.onDraw on
        // the UI thread still holds the previous bitmap reference for a few VSync frames until
        // Compose's update lambda swaps it. Calling recycle() here led to drawBitmap() hitting
        // a freed pixel buffer → SIGSEGV crash, with no logcat reaching disk because the
        // process died before the async writer flushed. The bitmap is heap-allocated and
        // collected by GC once the renderer drops its reference; the ~8 MB transient
        // duplication is harmless.
        return fresh
    }

    fun notifyDirty(x: Int, y: Int, w: Int, h: Int) {
        _dirty.tryEmit(Rect(x, y, x + w, y + h))
    }

    fun release() {
        // Same reason as resize(): don't recycle, let GC reclaim.
        _current.value = null
    }
}
