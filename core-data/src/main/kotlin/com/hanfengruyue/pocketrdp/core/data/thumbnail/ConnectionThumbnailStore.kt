package com.hanfengruyue.pocketrdp.core.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores one downscaled JPEG per connection — a thumbnail of the remote desktop, captured live from
 * the framebuffer during a session and shown on the connection list card (issue: 读取被控电脑桌面
 * 图片放到选项中).
 *
 * Files live under `<filesDir>/thumbnails/conn_<id>.jpg`. The id-keyed filename means no DB column /
 * migration is needed — the card just probes for the file. Saves run on this store's OWN IO scope
 * (not the caller's [androidx.lifecycle.ViewModel] scope) so a capture fired right as the session is
 * being torn down still completes after the ViewModel is cleared. Writes are atomic (temp file +
 * rename) so the list never decodes a half-written JPEG.
 */
@Singleton
class ConnectionThumbnailStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Survives ViewModel teardown: the final pre-disconnect capture is launched here, after which
    // SessionViewModel.onCleared cancels its own viewModelScope — that must NOT cancel the encode.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(): File = File(context.filesDir, THUMB_DIR).apply { if (!exists()) mkdirs() }

    /** Deterministic file for a connection id (may not exist yet). */
    fun fileFor(id: Long): File = File(dir(), "conn_$id.jpg")

    /** Epoch-millis the thumbnail was last written, or 0 if absent. Used to bust the Compose cache. */
    fun stamp(id: Long): Long = fileFor(id).let { if (it.exists()) it.lastModified() else 0L }

    /**
     * Persist [source] (already downscaled by [com.hanfengruyue.pocketrdp.core.rdp.BitmapBuffer.snapshot])
     * as the thumbnail for [id]. Fire-and-forget: encodes + writes on the store's IO scope. The
     * caller hands over ownership of [source]; we don't recycle it (it's a small, fresh copy).
     */
    fun save(id: Long, source: Bitmap) {
        if (id <= 0L) return
        scope.launch {
            runCatching {
                val target = fileFor(id)
                val tmp = File(target.parentFile, target.name + ".tmp")
                FileOutputStream(tmp).use { out ->
                    source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.flush()
                }
                if (!tmp.renameTo(target)) {
                    // renameTo can fail across some filesystems if the target exists; fall back to
                    // delete-then-rename so a stale thumbnail never blocks the fresh one.
                    target.delete()
                    if (!tmp.renameTo(target)) tmp.delete()
                }
            }.onFailure { PocketLogger.w(TAG, "thumbnail save failed for id=$id: ${it.message}") }
        }
    }

    /** Decode the thumbnail for [id] or null if absent / unreadable. Call off the main thread. */
    fun load(id: Long): Bitmap? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }
            .onFailure { PocketLogger.w(TAG, "thumbnail load failed for id=$id: ${it.message}") }
            .getOrNull()
    }

    /** Remove a connection's thumbnail (called when the connection itself is deleted). */
    fun delete(id: Long) {
        scope.launch { runCatching { fileFor(id).delete() } }
    }

    companion object {
        private const val TAG = "ThumbStore"
        private const val THUMB_DIR = "thumbnails"
        private const val JPEG_QUALITY = 80
    }
}
