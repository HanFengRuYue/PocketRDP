package com.hanfengruyue.pocketrdp.core.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded in-memory ring buffer backing [PocketLogger.entries].
 *
 * Avoids copying the ring into a fresh [List] on every log call when no UI collector is attached,
 * while hydrating subscribers immediately upon attachment and releasing the snapshot list once all
 * collectors detach.
 */
internal class LogEntryBuffer(
    private val capacity: Int,
    scope: CoroutineScope,
) {
    private val lock = Any()
    private val ring = ArrayDeque<PocketLogger.Entry>(capacity)
    private val generation = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<PocketLogger.Entry>>(emptyList())
    private var hasSubscribers = false
    private var entriesStale = false

    val entries: StateFlow<List<PocketLogger.Entry>> = _entries.asStateFlow()

    init {
        scope.launch {
            _entries.subscriptionCount.collect { count ->
                syncForSubscriberCount(active = count > 0)
            }
        }
    }

    fun currentGeneration(): Long = generation.get()

    fun append(entry: PocketLogger.Entry): Long = synchronized(lock) {
        val currentGeneration = generation.get()
        if (ring.size >= capacity) ring.removeFirst()
        ring.addLast(entry)
        if (hasSubscribers) {
            _entries.value = ring.toList()
            entriesStale = false
        } else {
            entriesStale = true
        }
        currentGeneration
    }

    fun clear(): Long = synchronized(lock) {
        val nextGeneration = generation.incrementAndGet()
        ring.clear()
        _entries.value = emptyList()
        entriesStale = false
        nextGeneration
    }

    fun snapshotWithGeneration(): Pair<Long, List<PocketLogger.Entry>> = synchronized(lock) {
        generation.get() to ring.toList()
    }

    private fun syncForSubscriberCount(active: Boolean) {
        synchronized(lock) {
            hasSubscribers = active
            if (active) {
                if (entriesStale) {
                    _entries.value = ring.toList()
                    entriesStale = false
                }
            } else if (_entries.value.isNotEmpty()) {
                _entries.value = emptyList()
                entriesStale = true
            }
        }
    }
}
