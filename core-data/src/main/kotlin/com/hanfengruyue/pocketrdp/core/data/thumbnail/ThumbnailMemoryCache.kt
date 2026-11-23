package com.hanfengruyue.pocketrdp.core.data.thumbnail

/**
 * Pure-Kotlin bounded LRU cache keyed by byte size with per-key mutation versions.
 *
 * Tracking a monotonic version per id prevents a slow disk decode that started before a concurrent
 * `save()` or `delete()` from overwriting fresher cache contents or resurrecting a deleted entry.
 * Evicted entries are dropped without calling [android.graphics.Bitmap.recycle] so Compose /
 * RenderThread never draws a recycled bitmap.
 */
internal class ThumbnailMemoryCache<V : Any>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<Long, Entry<V>>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val versions = mutableMapOf<Long, Long>()
    private var currentBytes = 0L

    data class LookupResult<V>(
        val value: V?,
        val version: Long,
    )

    fun get(id: Long): V? = synchronized(lock) { entries[id]?.value }

    fun getOrVersion(id: Long): LookupResult<V> = synchronized(lock) {
        LookupResult(
            value = entries[id]?.value,
            version = versions[id] ?: 0L,
        )
    }

    /**
     * Store [value] only if [id]'s version still matches [expectedVersion]. Returns the resulting
     * authoritative cached value (`value` on match, or the newer cached entry / `null` if a
     * concurrent mutation occurred).
     */
    fun putIfVersionMatches(id: Long, expectedVersion: Long, value: V): V? = synchronized(lock) {
        val currentVersion = versions[id] ?: 0L
        if (currentVersion != expectedVersion) {
            return@synchronized entries[id]?.value
        }
        putLocked(id, value)
        value
    }

    fun putAndAdvanceVersion(id: Long, value: V) {
        synchronized(lock) {
            versions[id] = (versions[id] ?: 0L) + 1L
            putLocked(id, value)
        }
    }

    fun removeAndAdvanceVersion(id: Long): Boolean = synchronized(lock) {
        versions[id] = (versions[id] ?: 0L) + 1L
        entries.remove(id)?.also { removed -> currentBytes -= removed.bytes } != null
    }

    fun sizeBytes(): Long = synchronized(lock) { currentBytes }

    private fun putLocked(id: Long, value: V) {
        val entryBytes = sizeOf(value).coerceAtLeast(1)
        entries.remove(id)?.let { previous -> currentBytes -= previous.bytes }
        if (entryBytes > maxBytes) return
        entries[id] = Entry(value, entryBytes)
        currentBytes += entryBytes
        trimToLimitLocked()
    }

    private fun trimToLimitLocked() {
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= eldest.value.bytes
            iterator.remove()
        }
    }

    private data class Entry<V>(
        val value: V,
        val bytes: Int,
    )

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
