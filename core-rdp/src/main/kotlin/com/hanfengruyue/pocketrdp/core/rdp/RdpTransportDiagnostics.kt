package com.hanfengruyue.pocketrdp.core.rdp

import java.util.Locale

/**
 * Stable, redacted diagnostic text for the application log.
 *
 * Control state deliberately excludes traffic counters so callers can log transitions without
 * producing one line per metrics poll. Neither formatter accepts endpoint or authentication data.
 */
internal fun RdpTransportSnapshot.controlDiagnostic(): String = buildString {
    append("phase=").append(phase.name)
    append(" requested=").append(formatTransportMask(requestedMask))
    append(" ready=").append(formatTransportMask(readyMask))
    append(" active=").append(formatTransportMask(activeMask))
    append(" softSync=").append(formatTransportMask(softSyncMask))
    append(" flags=").append(formatTransportFlags(flags))
    append(" selectedProtocol=").append(formatHex32(securityProtocol))
    append(" routes=").append(routedDvcCount)
    append(" failure=").append(failure.name).append('(').append(failure.wireValue).append(')')
    append(" hresult=").append(if (tunnelHresult == 0L) "none" else formatHex32(tunnelHresult))
    append(" socketError=").append(socketError)
    append(" reliable=").append(reliable.controlDiagnostic())
    append(" lossy=").append(lossy.controlDiagnostic())
}

internal fun RdpTransportSnapshot.trafficDiagnostic(): String =
    "reliable={${reliable.trafficDiagnostic()}} lossy={${lossy.trafficDiagnostic()}}"

internal fun RdpTransportSnapshot.hasTransportEvidence(): Boolean =
    requestedMask != 0L || readyMask != 0L || activeMask != 0L || softSyncMask != 0L ||
        failure != RdpTransportFailure.NONE || downgradedToTcp || reconnectRequested ||
        reliable.kind != RdpUdpKind.UNKNOWN || lossy.kind != RdpUdpKind.UNKNOWN ||
        reliable.state != RdpUdpTunnelState.IDLE || lossy.state != RdpUdpTunnelState.IDLE

private fun RdpUdpTunnelSnapshot.controlDiagnostic(): String =
    "${kind.name}/${state.name}/version=${formatProtocolVersion(protocolVersion)}"

private fun RdpUdpTunnelSnapshot.trafficDiagnostic(): String =
    "kind=${kind.name} state=${state.name} " +
        "rxBytes=$receivedBytes txBytes=$sentBytes " +
        "rxPackets=$receivedPackets txPackets=$sentPackets " +
        "retransmits=$retransmits fecRecovered=$fecRecovered dropped=$droppedPackets " +
        "srttMicros=$smoothedRttMicros idleMillis=$idleMillis"

private fun formatTransportMask(mask: Long): String {
    if (mask == 0L) return "none"
    val labels = buildList {
        if (mask and RdpTransportSnapshot.MASK_UDP_R != 0L) add("UDP-R")
        if (mask and RdpTransportSnapshot.MASK_UDP_L != 0L) add("UDP-L")
        if (mask and RdpTransportSnapshot.MASK_UDP2 != 0L) add("UDP2")
        val unknown = mask and KNOWN_TRANSPORT_MASK.inv()
        if (unknown != 0L) add("unknown(${formatHex32(unknown)})")
    }
    return labels.joinToString("|")
}

private fun formatTransportFlags(flags: Long): String {
    if (flags == 0L) return "none"
    val labels = buildList {
        if (flags and RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP != 0L) {
            add("downgraded-to-tcp")
        }
        if (flags and RdpTransportSnapshot.FLAG_RECONNECT_REQUESTED != 0L) {
            add("reconnect-requested")
        }
        val unknown = flags and KNOWN_TRANSPORT_FLAGS.inv()
        if (unknown != 0L) add("unknown(${formatHex32(unknown)})")
    }
    return labels.joinToString("|")
}

private fun formatProtocolVersion(version: Long): String =
    if (version == 0L) "none" else "0x${hex(version, 4)}"

private fun formatHex32(value: Long): String = "0x${hex(value and UINT32_MASK, 8)}"

private fun hex(value: Long, width: Int): String =
    java.lang.Long.toHexString(value)
        .uppercase(Locale.ROOT)
        .padStart(width, '0')

private const val UINT32_MASK = 0xFFFF_FFFFL
private const val KNOWN_TRANSPORT_MASK =
    RdpTransportSnapshot.MASK_UDP_R or
        RdpTransportSnapshot.MASK_UDP_L or
        RdpTransportSnapshot.MASK_UDP2
private const val KNOWN_TRANSPORT_FLAGS =
    RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP or
        RdpTransportSnapshot.FLAG_RECONNECT_REQUESTED
