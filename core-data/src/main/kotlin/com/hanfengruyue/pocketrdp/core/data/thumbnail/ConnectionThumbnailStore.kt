package com.hanfengruyue.pocketrdp.core.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores one downscaled JPEG per connection — a thumbnail of the remote desktop, captured live from
 * the framebuffer during a session and shown on the connection list card (issue: 读取被控电脑桌面
 * 图片放到选项中).
 *
 * Files live under `<noBackupFilesDir>/thumbnails/conn_<id>.jpg`. The id-keyed filename means no DB column /
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
    // Every save uses the same deterministic .tmp path for a connection. Serialize all store
    // operations so periodic and final-disconnect captures cannot truncate or rename that file
    // concurrently, and so a delete cannot race a still-running save.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _revision = MutableStateFlow(0L)

    /** Advances only after a save/delete completes, allowing visible cards to invalidate immediately. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val directory: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(context.noBackupFilesDir, THUMB_DIR).apply {
            if (!exists()) mkdirs()
            migrateLegacyThumbnails(this)
        }
    }

    private fun dir(): File = directory

    private fun migrateLegacyThumbnails(targetDir: File) {
        val legacyDir = File(context.filesDir, THUMB_DIR)
        if (!legacyDir.exists()) return
        legacyDir.listFiles()?.forEach { source ->
            if (!source.isFile) return@forEach
            val target = File(targetDir, source.name)
            if (!target.exists() && !source.renameTo(target)) {
                runCatching {
                    source.copyTo(target)
                    source.delete()
                }
            } else if (target.exists()) {
                source.delete()
            }
        }
        legacyDir.delete()
    }

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
            val target = fileFor(id)
            val tmp = File(target.parentFile, target.name + ".tmp")
            runCatching {
                FileOutputStream(tmp).use { out ->
                    if (!source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        throw IOException("JPEG encoder rejected thumbnail")
                    }
                    out.flush()
                }
                if (!tmp.renameTo(target)) {
                    // renameTo can fail across some filesystems if the target exists; fall back to
                    // delete-then-rename so a stale thumbnail never blocks the fresh one.
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        throw IOException("could not atomically replace thumbnail")
                    }
                }
            }.onSuccess {
                _revision.update { revision -> revision + 1L }
            }.onFailure {
                tmp.delete()
                PocketLogger.w(TAG, "thumbnail save failed for id=$id: ${it.message}")
            }
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
        scope.launch {
            runCatching { fileFor(id).delete() }
                .onSuccess { _revision.update { revision -> revision + 1L } }
        }
    }

    companion object {
        private const val TAG = "ThumbStore"
        private const val THUMB_DIR = "thumbnails"
        // Bumped 80 → 92 together with the capture resolution (SessionViewModel.THUMB_MAX_DIM 640 →
        // 1280) to kill the connection-card blur (用户反馈: 主页图片非常模糊). The card spans nearly the
        // full screen width, so the old 640px/q80 JPEG was upscaled ~2× and looked soft; 1280px/q92
        // displays ~1:1 on phones. ~100–200 KB per thumbnail — still trivially small.
        private const val JPEG_QUALITY = 92
    }
}
