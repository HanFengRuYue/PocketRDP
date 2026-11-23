package com.hanfengruyue.pocketrdp.feature.session

import android.os.SystemClock

/**
 * Sliding-window FPS counter backed by a primitive [LongArray] ring buffer (zero boxing on ticks).
 * Caller invokes [tick] from the high-frequency frame producer (~30-60 Hz) and the UI samples
 * [snapshot] from the 1 Hz metrics ticker.
 *
 * Thread-safe via synchronization (producer runs on Dispatchers.Default, consumer on Main).
 */
class FpsCounter(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private var timestamps = LongArray(INITIAL_CAPACITY)
    private var head = 0
    private var count = 0

    @Synchronized
    fun tick() {
        val now = nowMs()
        evictExpired(now - windowMs)
        if (count == timestamps.size) grow()
        val tail = (head + count) % timestamps.size
        timestamps[tail] = now
        count++
    }

    @Synchronized
    fun snapshot(): Int {
        evictExpired(nowMs() - windowMs)
        return count
    }

    @Synchronized
    fun reset() {
        head = 0
        count = 0
    }

    private fun evictExpired(cutoff: Long) {
        val cap = timestamps.size
        while (count > 0 && timestamps[head] < cutoff) {
            head = (head + 1) % cap
            count--
        }
    }

    private fun grow() {
        val old = timestamps
        val next = LongArray(old.size * 2)
        val firstChunk = minOf(count, old.size - head)
        old.copyInto(next, destinationOffset = 0, startIndex = head, endIndex = head + firstChunk)
        val remainder = count - firstChunk
        if (remainder > 0) {
            old.copyInto(next, destinationOffset = firstChunk, startIndex = 0, endIndex = remainder)
        }
        timestamps = next
        head = 0
    }

    companion object {
        private const val DEFAULT_WINDOW_MS = 1_000L
        private const val INITIAL_CAPACITY = 128
    }
}
