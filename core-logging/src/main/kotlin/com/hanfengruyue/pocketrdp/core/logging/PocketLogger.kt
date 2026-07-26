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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-wide logger.
 *
 * - Single point of entry: callers use [d]/[i]/[w]/[e] just like android.util.Log.
 * - Also forwards every entry to Logcat so `adb logcat -s PocketRDP` keeps working.
 * - Keeps the most recent [MEMORY_CAPACITY] entries in memory as a StateFlow so the in-app
 *   LogScreen can subscribe and render them live.
 * - Asynchronously appends to `<noBackupFilesDir>/logs/pocketrdp.log` via a single-consumer channel
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
    private const val MAX_MESSAGE_CHARS = 8_192
    private const val MAX_THROWABLE_CHARS = 32_768
    private const val MAX_EXPORTED_LOGS = 8

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
    private val fileLock = Any()

    private val appContextRef = AtomicReference<Context?>(null)
    private val logGeneration = AtomicLong(0L)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel: Channel<PersistRequest> = Channel(capacity = 256)

    private data class PersistRequest(
        val entry: Entry,
        val generation: Long,
    )

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
        synchronized(fileLock) { migrateLegacyLogs(app) }
        writeScope.launch { runWriter(app) }
        i(TAG, "PocketLogger installed")
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)

    private fun log(level: Level, tag: String, message: String, t: Throwable?) {
        val safeMessage = sanitize(message).bounded(MAX_MESSAGE_CHARS)
        val safeThrowable = t?.let {
            sanitize(Log.getStackTraceString(it)).bounded(MAX_THROWABLE_CHARS)
        }
        val entry = Entry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = safeMessage,
            throwableText = safeThrowable,
        )
        // Logcat mirror.
        when (level) {
            Level.DEBUG -> Log.d(tag, safeMessage)
            Level.INFO -> Log.i(tag, safeMessage)
            Level.WARN -> Log.w(tag, safeMessage)
            Level.ERROR -> Log.e(tag, safeMessage)
        }
        val generation: Long
        synchronized(ringLock) {
            generation = logGeneration.get()
            if (ring.size >= MEMORY_CAPACITY) ring.removeFirst()
            ring.addLast(entry)
            _entries.value = ring.toList()
        }
        // Best-effort: drop on overflow rather than block the caller.
        writeChannel.trySend(PersistRequest(entry, generation))
    }

    /** Wipe both the in-memory tail and the on-disk log files. */
    fun clear() {
        synchronized(ringLock) {
            synchronized(fileLock) {
                logGeneration.incrementAndGet()
                ring.clear()
                _entries.value = emptyList()
                appContextRef.get()?.let { ctx ->
                    runCatching {
                        logFile(ctx).delete()
                        logBackup(ctx).delete()
                        File(ctx.cacheDir, LOG_DIR)
                            .listFiles()
                            ?.filter(File::isFile)
                            ?.forEach(File::delete)
                    }
                }
            }
        }
    }

    /**
     * Build (or refresh) a shareable copy of the log under `<cacheDir>/logs/`. Returning a
     * File rather than a Uri lets the UI layer hand it to FileProvider on its own terms.
     */
    fun snapshotForExport(context: Context): File {
        val ctx = context.applicationContext
        val (memoryGeneration, memoryEntries) = synchronized(ringLock) {
            logGeneration.get() to ring.toList()
        }
        return synchronized(fileLock) {
            val cacheDir = File(ctx.cacheDir, LOG_DIR).apply { mkdirs() }
            val stamp = DATE_FMT.get()!!.format(Date())
            // A fresh path for every share keeps an earlier recipient's temporary URI grant from
            // observing a later, overwritten snapshot. Retain only a small recent set so repeated
            // exports cannot grow the cache indefinitely.
            val out = File.createTempFile("pocketrdp-$stamp-", ".log", cacheDir)
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("PocketRDP log snapshot $stamp\n")
                writer.write("-------------------------------------\n")
                val live = logFile(ctx)
                val backup = logBackup(ctx)
                if (backup.exists()) writer.write(backup.readText(Charsets.UTF_8))
                if (live.exists()) writer.write(live.readText(Charsets.UTF_8))
                // Also flush whatever is in the ring but might not be on disk yet. If a clear
                // happened after the copy, do not resurrect pre-clear entries into the export.
                writer.write("--- in-memory tail ---\n")
                if (memoryGeneration == logGeneration.get()) {
                    memoryEntries.forEach { writer.write(it.format() + "\n") }
                }
            }
            cacheDir.listFiles()
                ?.asSequence()
                ?.filter {
                    it.isFile && it.name.startsWith("pocketrdp-") && it.name.endsWith(".log")
                }
                ?.sortedByDescending(File::lastModified)
                ?.drop(MAX_EXPORTED_LOGS)
                ?.forEach(File::delete)
            out
        }
    }

    private suspend fun runWriter(ctx: Context) {
        val dir = logDir(ctx)
        dir.mkdirs()
        val file = logFile(ctx)
        for (request in writeChannel) {
            try {
                synchronized(fileLock) {
                    if (request.generation != logGeneration.get()) return@synchronized
                    if (file.length() > MAX_FILE_BYTES) rotate(ctx)
                    file.appendText(request.entry.format() + "\n", Charsets.UTF_8)
                }
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
        if (live.exists() && !live.renameTo(backup)) {
            // A failed rename must not disable the size cap indefinitely. Keep a best-effort
            // snapshot, then truncate the live file while the writer's file lock is held.
            runCatching { live.copyTo(backup, overwrite = true) }
            runCatching { live.writeText("", Charsets.UTF_8) }
        }
    }

    private fun logDir(ctx: Context): File = File(ctx.noBackupFilesDir, LOG_DIR)
    private fun logFile(ctx: Context): File = File(logDir(ctx), LOG_FILE)
    private fun logBackup(ctx: Context): File = File(logDir(ctx), LOG_FILE_BACKUP)

    private fun migrateLegacyLogs(ctx: Context) {
        val legacyDir = File(ctx.filesDir, LOG_DIR)
        if (!legacyDir.exists()) return
        val targetDir = logDir(ctx).apply { mkdirs() }
        listOf(LOG_FILE, LOG_FILE_BACKUP).forEach { name ->
            val source = File(legacyDir, name)
            if (!source.exists()) return@forEach
            val target = File(targetDir, name)
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

    private fun sanitize(value: String): String {
        val cliRedacted = CLI_ARGUMENT_PATTERN.replace(value) { match ->
            "${match.groupValues[1]}<redacted>"
        }
        return NAMED_VALUE_PATTERN.replace(cliRedacted) { match ->
            "${match.groupValues[1]}<redacted>"
        }
    }

    private val CLI_ARGUMENT_PATTERN =
        Regex("""(?i)(/(?:p|u|d|v|drive):)(?:"[^"]*"|'[^']*'|\S+)""")
    private val NAMED_VALUE_PATTERN =
        Regex("""(?i)(\b(?:password|passwd|username|user|domain|host|endpoint|path)\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)""")

    private fun String.bounded(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars) + "\n<truncated>"
}
