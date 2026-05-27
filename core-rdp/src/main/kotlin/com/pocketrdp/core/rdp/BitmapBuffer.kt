package com.pocketrdp.core.rdp

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
        old?.recycle()
        return fresh
    }

    fun notifyDirty(x: Int, y: Int, w: Int, h: Int) {
        _dirty.tryEmit(Rect(x, y, x + w, y + h))
    }

    fun release() {
        _current.value?.recycle()
        _current.value = null
    }
}
