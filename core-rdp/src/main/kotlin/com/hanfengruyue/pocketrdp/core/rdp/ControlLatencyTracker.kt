package com.hanfengruyue.pocketrdp.core.rdp

import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * End-to-end control-latency estimator (discrete input → decoded server frame).
 *
 * A discrete press (click / key / touch down) made while the screen was idle for [idleGapMs] is
 * armed atomically and closed by the next frame arrival. Responses outside [responseWindowMs]
 * (or arms that expire before a subsequent discrete input) are counted as discarded rather than
 * polluting the low-percentile ([percentile]) estimate.
 */
internal class ControlLatencyTracker(
    private val idleGapMs: Long = DEFAULT_IDLE_GAP_MS,
    private val responseWindowMs: Long = DEFAULT_RESPONSE_WINDOW_MS,
    sampleWindow: Int = DEFAULT_SAMPLE_WINDOW,
    private val percentile: Float = DEFAULT_PERCENTILE,
) {
    private val pendingInputAtMs = AtomicLong(0L)
    @Volatile private var lastServerFrameAtMs = 0L
    private val lock = Any()
    private val samples = IntArray(sampleWindow)
    private val scratch = IntArray(sampleWindow)
    private var sampleCount = 0
    private var sampleHead = 0
    private val acceptedCount = AtomicInteger(0)
    private val discardedCount = AtomicInteger(0)

    fun reset() {
        pendingInputAtMs.set(0L)
        lastServerFrameAtMs = 0L
        synchronized(lock) {
            sampleCount = 0
            sampleHead = 0
        }
        acceptedCount.set(0)
        discardedCount.set(0)
    }

    fun markDiscreteInput(nowMs: Long) {
        val pending = pendingInputAtMs.get()
        if (pending != 0L && nowMs - pending > responseWindowMs) {
            if (pendingInputAtMs.compareAndSet(pending, 0L)) {
                discardedCount.incrementAndGet()
            }
        }
        if (nowMs > 0L && nowMs - lastServerFrameAtMs > idleGapMs) {
            pendingInputAtMs.compareAndSet(0L, nowMs)
        }
    }

    fun recordServerFrame(nowMs: Long) {
        lastServerFrameAtMs = nowMs
        if (pendingInputAtMs.get() == 0L) return
        val sent = pendingInputAtMs.getAndSet(0L)
        if (sent == 0L) return
        val sample = nowMs - sent
        if (sample < 0L || sample > responseWindowMs) {
            discardedCount.incrementAndGet()
            return
        }
        acceptedCount.incrementAndGet()
        synchronized(lock) {
            samples[sampleHead] = sample.toInt()
            sampleHead = (sampleHead + 1) % samples.size
            if (sampleCount < samples.size) sampleCount++
        }
    }

    fun estimateMs(): Int = synchronized(lock) {
        val n = sampleCount
        if (n == 0) return -1
        System.arraycopy(samples, 0, scratch, 0, n)
        Arrays.sort(scratch, 0, n)
        val idx = ((n - 1) * percentile).roundToInt().coerceIn(0, n - 1)
        scratch[idx]
    }

    fun accepted(): Int = acceptedCount.get()

    fun discarded(): Int = discardedCount.get()

    companion object {
        // Only arm a sample when the screen has been idle this long (so the next frame is a
        // genuine response, not one already streaming).
        const val DEFAULT_IDLE_GAP_MS = 80L

        // A press→frame gap beyond this is treated as "no genuine response" and discarded.
        const val DEFAULT_RESPONSE_WINDOW_MS = 300L

        // Recent-sample ring + low percentile (P25 = trustworthy low end without min-lock).
        const val DEFAULT_SAMPLE_WINDOW = 20
        const val DEFAULT_PERCENTILE = 0.25f
    }
}

/**
 * Rolling median decode→present delay estimator fed by `RdpSurface` on each newly-drawn frame.
 */
internal class PresentLagTracker(
    sampleWindow: Int = DEFAULT_SAMPLE_WINDOW,
    private val maxLagMs: Long = DEFAULT_MAX_LAG_MS,
) {
    private val lock = Any()
    private val samples = IntArray(sampleWindow)
    private val scratch = IntArray(sampleWindow)
    private var count = 0
    private var head = 0

    fun reset() {
        synchronized(lock) {
            count = 0
            head = 0
        }
    }

    fun record(lagMs: Long) {
        if (lagMs < 0L || lagMs > maxLagMs) return
        synchronized(lock) {
            samples[head] = lagMs.toInt()
            head = (head + 1) % samples.size
            if (count < samples.size) count++
        }
    }

    fun medianMs(): Int = synchronized(lock) {
        val n = count
        if (n == 0) return -1
        System.arraycopy(samples, 0, scratch, 0, n)
        Arrays.sort(scratch, 0, n)
        scratch[n / 2]
    }

    companion object {
        const val DEFAULT_SAMPLE_WINDOW = 30
        const val DEFAULT_MAX_LAG_MS = 1000L
    }
}
