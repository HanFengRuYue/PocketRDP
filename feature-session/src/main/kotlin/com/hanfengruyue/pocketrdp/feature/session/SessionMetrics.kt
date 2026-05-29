package com.hanfengruyue.pocketrdp.feature.session

import android.os.SystemClock

/**
 * Sliding-window FPS counter. Caller invokes [tick] from the high-frequency producer
 * (OnGraphicsUpdate, ~30-60 Hz) and the UI samples [snapshot] from a 1 Hz ticker.
 *
 * Decoupling the producer from state writes is the whole point of this class — if
 * SessionViewModel did `_state.update { it.copy(fps = ...) }` on every frame, Compose
 * would recompose the TopAppBar dozens of times per second.
 *
 * Thread-safe via synchronization (producer is the FreeRDP worker thread, consumer
 * runs on viewModelScope.Dispatchers.Main).
 */
class FpsCounter(private val windowMs: Long = 1_000L) {

    private val timestamps = ArrayDeque<Long>(96)

    @Synchronized
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        timestamps.addLast(now)
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) timestamps.removeFirst()
    }

    @Synchronized
    fun snapshot(): Int {
        val now = SystemClock.elapsedRealtime()
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) timestamps.removeFirst()
        return timestamps.size
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
    }
}
