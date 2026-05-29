package com.hanfengruyue.pocketrdp.core.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-wide logger.
 *
 * - Single point of entry: callers use [d]/[i]/[w]/[e] just like android.util.Log.
 * - Also forwards every entry to Logcat so `adb logcat -s PocketRDP` keeps working.
 * - Keeps the most recent [MEMORY_CAPACITY] entries in memory as a StateFlow so the in-app
 *   LogScreen can subscribe and render them live.
 * - Asynchronously appends to `<filesDir>/logs/pocketrdp.log` via a single-consumer channel
 *   so logging on the native FreeRDP worker thread doesn't block on file I/O. The file is
 *   rotated when it crosses [MAX_FILE_BYTES] (one .1 backup is kept).
 *
 * Call [install] once from Application.onCreate. Logs emitted before install are dropped
 * (only the in-memory tail is retained from then on).
 */
object PocketLogger {

    private const val TAG = "PocketRDP"
    private const val MEMORY_CAPACITY = 1000
    private const val MAX_FILE_BYTES = 1_500_000L  // ~1.5 MB
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "pocketrdp.log"
    private const val LOG_FILE_BACKUP = "pocketrdp.log.1"

    enum class Level(val tag: String, val androidPriority: Int) {
        DEBUG("D", Log.DEBUG),
        INFO("I", Log.INFO),
        WARN("W", Log.WARN),
        ERROR("E", Log.ERROR),
    }

    data class Entry(
        val timestampMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwableText: String? = null,
    ) {
        fun format(): String {
            val time = TIME_FMT.get()!!.format(Date(timestampMillis))
            val base = "$time ${level.tag}/$tag: $message"
            return if (throwableText.isNullOrEmpty()) base else "$base\n$throwableText"
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val ring: ArrayDeque<Entry> = ArrayDeque(MEMORY_CAPACITY)
    private val ringLock = Any()

    private val appContextRef = AtomicReference<Context?>(null)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel: Channel<Entry> = Channel(capacity = 256)

    private val TIME_FMT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private val DATE_FMT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    /** Wire up file persistence. Safe to call multiple times; only the first install runs. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (!appContextRef.compareAndSet(null, app)) return
        writeScope.launch { runWriter(app) }
        i(TAG, "PocketLogger installed at ${logDir(app).absolutePath}")
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)

    private fun log(level: Level, tag: String, message: String, t: Throwable?) {
        val entry = Entry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwableText = t?.let { Log.getStackTraceString(it) },
        )
        // Logcat mirror.
        when (level) {
            Level.DEBUG -> Log.d(tag, message, t)
            Level.INFO -> Log.i(tag, message, t)
            Level.WARN -> Log.w(tag, message, t)
            Level.ERROR -> Log.e(tag, message, t)
        }
        synchronized(ringLock) {
            if (ring.size >= MEMORY_CAPACITY) ring.removeFirst()
            ring.addLast(entry)
            _entries.value = ring.toList()
        }
        // Best-effort: drop on overflow rather than block the caller.
        writeChannel.trySend(entry)
    }

    /** Wipe both the in-memory tail and the on-disk log files. */
    fun clear() {
        synchronized(ringLock) {
            ring.clear()
            _entries.value = emptyList()
        }
        val ctx = appContextRef.get() ?: return
        writeScope.launch {
            runCatching {
                logFile(ctx).delete()
                logBackup(ctx).delete()
            }
        }
    }

    /**
     * Build (or refresh) a shareable copy of the log under `<cacheDir>/logs/`. Returning a
     * File rather than a Uri lets the UI layer hand it to FileProvider on its own terms.
     */
    fun snapshotForExport(context: Context): File {
        val ctx = context.applicationContext
        val cacheDir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val stamp = DATE_FMT.get()!!.format(Date())
        val out = File(cacheDir, "pocketrdp-$stamp.log")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("PocketRDP log snapshot $stamp\n")
            writer.write("-------------------------------------\n")
            val live = logFile(ctx)
            val backup = logBackup(ctx)
            if (backup.exists()) writer.write(backup.readText(Charsets.UTF_8))
            if (live.exists()) writer.write(live.readText(Charsets.UTF_8))
            // Also flush whatever is in the ring but might not be on disk yet.
            writer.write("--- in-memory tail ---\n")
            synchronized(ringLock) { ring.forEach { writer.write(it.format() + "\n") } }
        }
        return out
    }

    private suspend fun runWriter(ctx: Context) {
        val dir = logDir(ctx)
        dir.mkdirs()
        val file = logFile(ctx)
        for (entry in writeChannel) {
            try {
                if (file.length() > MAX_FILE_BYTES) rotate(ctx)
                file.appendText(entry.format() + "\n", Charsets.UTF_8)
            } catch (io: IOException) {
                // If file logging fails, fall back to Logcat-only mode silently.
                Log.w(TAG, "log write failed: ${io.message}")
            }
        }
    }

    private fun rotate(ctx: Context) {
        val live = logFile(ctx)
        val backup = logBackup(ctx)
        if (backup.exists()) backup.delete()
        live.renameTo(backup)
    }

    private fun logDir(ctx: Context): File = File(ctx.filesDir, LOG_DIR)
    private fun logFile(ctx: Context): File = File(logDir(ctx), LOG_FILE)
    private fun logBackup(ctx: Context): File = File(logDir(ctx), LOG_FILE_BACKUP)
}
