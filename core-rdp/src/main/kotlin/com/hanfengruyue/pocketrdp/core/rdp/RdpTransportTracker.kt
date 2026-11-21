package com.hanfengruyue.pocketrdp.core.rdp

import android.os.SystemClock
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks redacted RDP-UDP diagnostic logging and the per-session UDP-to-TCP downgrade latch.
 *
 * Only protocol state, numeric errors and aggregate counters are formatted; endpoint or
 * authentication data is never accepted.
 */
internal class RdpTransportTracker(
    private val logTag: String,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private val downgradeLatch = UdpDowngradeLatch()
    private var multitransportRequestedForConnection: Boolean = false
    private var transportConnectedAtMs: Long = 0L
    private var transportRequestObserved: Boolean = false
    private var missingTransportRequestLogged: Boolean = false
    private var incompatibleTransportSnapshotLogged: Boolean = false
    private var lastLoggedTransportControl: String? = null
    private var lastTransportTrafficLogMs: Long = 0L

    fun hasObservedDowngrade(): Boolean = downgradeLatch.hasObservedDowngrade()

    fun effectiveMultitransport(requested: Boolean): Boolean =
        downgradeLatch.effectiveMultitransport(requested)

    fun observeDowngrade(snapshot: RdpTransportSnapshot): Boolean =
        downgradeLatch.observe(snapshot)

    fun reset(requestedByApp: Boolean) {
        synchronized(lock) {
            multitransportRequestedForConnection = requestedByApp
            transportConnectedAtMs = 0L
            transportRequestObserved = false
            missingTransportRequestLogged = false
            incompatibleTransportSnapshotLogged = false
            lastLoggedTransportControl = null
            lastTransportTrafficLogMs = 0L
        }
    }

    fun markConnected() {
        synchronized(lock) {
            transportConnectedAtMs = elapsedRealtimeMs()
        }
    }

    fun decodeAndLog(
        raw: LongArray?,
        source: String,
        force: Boolean,
    ): RdpTransportSnapshot {
        val snapshot = RdpTransportSnapshot.decode(raw)
        downgradeLatch.observe(snapshot)
        val shouldTrack = synchronized(lock) {
            multitransportRequestedForConnection || snapshot.hasTransportEvidence()
        }
        if (!shouldTrack || raw == null) return snapshot

        if (!snapshot.knownVersion) {
            val shouldLog = synchronized(lock) {
                if (incompatibleTransportSnapshotLogged) {
                    false
                } else {
                    incompatibleTransportSnapshotLogged = true
                    true
                }
            }
            if (shouldLog) {
                val version = raw.firstOrNull()?.toString() ?: "missing"
                PocketLogger.w(
                    logTag,
                    "RDP-UDP diagnostic source=$source incompatible snapshot " +
                        "fieldCount=${raw.size} version=$version " +
                        "expectedFieldCount=${RdpTransportSnapshot.FIELD_COUNT} " +
                        "expectedVersion=${RdpTransportSnapshot.VERSION}; " +
                        "treating transport as TCP/unknown",
                )
            }
            return snapshot
        }

        logSnapshot(snapshot, source, force)
        return snapshot
    }

    private fun logSnapshot(
        snapshot: RdpTransportSnapshot,
        source: String,
        force: Boolean,
    ) {
        val now = elapsedRealtimeMs()
        val control = snapshot.controlDiagnostic()
        var logMissingRequest = false
        var logControlState = false
        var logTrafficHeartbeat = false
        synchronized(lock) {
            if (snapshot.hasTransportEvidence()) transportRequestObserved = true
            if (markMissingTransportRequestIfOverdue(now)) {
                logMissingRequest = true
            }

            if (force || control != lastLoggedTransportControl) {
                lastLoggedTransportControl = control
                lastTransportTrafficLogMs = now
                logControlState = true
            } else if (snapshot.udpActive &&
                now - lastTransportTrafficLogMs >= TRANSPORT_TRAFFIC_LOG_INTERVAL_MS
            ) {
                lastTransportTrafficLogMs = now
                logTrafficHeartbeat = true
            }
        }

        if (logMissingRequest) {
            PocketLogger.w(
                logTag,
                "RDP-UDP diagnostic: no server multitransport request was observed within " +
                    "${MULTITRANSPORT_REQUEST_WAIT_MS / MILLIS_PER_SECOND}s after TCP connected; " +
                    "the server or network path may not be offering RDP-UDP",
            )
        }

        if (logControlState) {
            val message =
                "RDP-UDP diagnostic source=$source $control; ${snapshot.trafficDiagnostic()}"
            val isFailurePhase = snapshot.phase == RdpTransportPhase.UDP_FAILED ||
                snapshot.phase == RdpTransportPhase.RECONNECTING_TCP
            val failed = snapshot.failure != RdpTransportFailure.NONE ||
                isFailurePhase ||
                snapshot.downgradedToTcp
            if (failed) PocketLogger.w(logTag, message) else PocketLogger.i(logTag, message)
        } else if (logTrafficHeartbeat) {
            PocketLogger.d(
                logTag,
                "RDP-UDP traffic heartbeat ${snapshot.trafficDiagnostic()}",
            )
        }
    }

    /** Must be called while [lock] is held. */
    private fun markMissingTransportRequestIfOverdue(now: Long): Boolean {
        if (!multitransportRequestedForConnection ||
            transportRequestObserved ||
            missingTransportRequestLogged
        ) {
            return false
        }
        if (transportConnectedAtMs <= 0L ||
            now - transportConnectedAtMs < MULTITRANSPORT_REQUEST_WAIT_MS
        ) {
            return false
        }
        missingTransportRequestLogged = true
        return true
    }

    companion object {
        private const val MULTITRANSPORT_REQUEST_WAIT_MS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val TRANSPORT_TRAFFIC_LOG_INTERVAL_MS = 30_000L
    }
}

internal class UdpDowngradeLatch {
    private val observed = AtomicBoolean(false)

    fun observe(snapshot: RdpTransportSnapshot): Boolean {
        if (snapshot.indicatesUdpDowngrade()) {
            observed.set(true)
        }
        return observed.get()
    }

    fun hasObservedDowngrade(): Boolean = observed.get()

    fun effectiveMultitransport(requested: Boolean): Boolean =
        requested && !observed.get()
}

internal fun RdpTransportSnapshot.indicatesUdpDowngrade(): Boolean {
    if (!knownVersion) return false
    val failedPhase = phase == RdpTransportPhase.RECONNECTING_TCP ||
        phase == RdpTransportPhase.UDP_FAILED
    return downgradedToTcp || reconnectRequested || failedPhase
}
