package com.hanfengruyue.pocketrdp.core.rdp

enum class InputMode {
    /**
     * Native Windows multi-touch — every finger is forwarded as an RDPEI touch contact
     * (TouchBegin/Update/End) so Windows handles tap/scroll/pinch/rotate itself, exactly like a
     * physical touchscreen. NO mouse emulation. Requires the rdpei dynamic channel (negotiated via
     * `/multitouch`) and the native touch JNI; falls back gracefully (drops contacts) if the server
     * never brings the channel up.
     */
    TOUCH,

    /** Phone-as-trackpad — single-finger drags move a virtual mouse cursor with acceleration. */
    TRACKPAD,
}

object RdpPointerFlags {
    const val MOVE = 0x0800
    const val DOWN = 0x8000
    const val BUTTON1 = 0x1000
    const val BUTTON2 = 0x2000
    const val BUTTON3 = 0x4000
    const val WHEEL = 0x0200
    const val WHEEL_NEGATIVE = 0x0100
}

/**
 * Touch-contact lifecycle actions, mirrored on the native side (android_event.c). Each maps to an
 * RDPEI client call: DOWN→TouchBegin, MOVE→TouchUpdate, UP→TouchEnd. Values MUST stay in sync with
 * the C switch in `freerdp_send_touch` / `android_process_event`.
 */
object RdpTouchAction {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
}

/**
 * Negotiated network transport, surfaced for the session status badge (issue: "显示目前 TCP/UDP").
 * Resolved from the native [RdpClient.transportInfo] bitfield. The value is based on an established
 * transport/tunnel state, not merely on multitransport capability flags.
 */
enum class RdpTransportPhase(val wireValue: Long) {
    TCP(0),
    NEGOTIATING(1),
    WAITING_SOFT_SYNC(2),
    ACTIVE(3),
    UDP_FAILED(4),
    RECONNECTING_TCP(5),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromWire(value: Long): RdpTransportPhase = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

enum class RdpUdpKind(val mask: Long) {
    UDP_R(1),
    UDP_L(2),
    UDP2(4),
    UNKNOWN(0),
    ;

    companion object {
        fun fromMask(mask: Long): RdpUdpKind = entries.firstOrNull { it.mask == mask } ?: UNKNOWN
    }
}

enum class RdpUdpTunnelState(val wireValue: Long) {
    IDLE(0),
    SYN_SENT(1),
    SECURITY_HANDSHAKE(2),
    RDPEMT_CREATE(3),
    READY(4),
    ACTIVE(5),
    FAILED(6),
    CLOSED(7),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromWire(value: Long): RdpUdpTunnelState = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

enum class RdpTransportFailure(val wireValue: Long) {
    NONE(0),
    UNSUPPORTED_PROTOCOL(1),
    INVALID_ENDPOINT(2),
    SOCKET_CONNECT(3),
    SYN_SEND(4),
    SYN_TIMEOUT(5),
    SYN_RECEIVE(6),
    SYN_PARSE(7),
    RESOURCE_LIMIT(8),
    FINAL_ACK(9),
    UNSUPPORTED_VERSION(10),
    SECURITY_HANDSHAKE(11),
    RDPEMT_REQUEST(12),
    RDPEMT_RESPONSE(13),
    RDPEMT_REJECTED(14),
    TRANSPORT_TIMEOUT(15),
    UNSUPPORTED_ROUTE(16),
    INVALID_DVC_ROUTE(101),
    DVC_DELIVERY(102),
    TUNNEL_POLL(103),
    TUNNEL_WRITE(104),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromWire(value: Long): RdpTransportFailure = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class RdpUdpTunnelSnapshot(
    val kind: RdpUdpKind = RdpUdpKind.UNKNOWN,
    val state: RdpUdpTunnelState = RdpUdpTunnelState.IDLE,
    val protocolVersion: Long = 0,
    val receivedBytes: Long = 0,
    val sentBytes: Long = 0,
    val receivedPackets: Long = 0,
    val sentPackets: Long = 0,
    val retransmits: Long = 0,
    val fecRecovered: Long = 0,
    val droppedPackets: Long = 0,
    val smoothedRttMicros: Long = 0,
    val idleMillis: Long = 0,
)

data class RdpTransportSnapshot(
    val knownVersion: Boolean = false,
    val phase: RdpTransportPhase = RdpTransportPhase.TCP,
    val requestedMask: Long = 0,
    val readyMask: Long = 0,
    val activeMask: Long = 0,
    val softSyncMask: Long = 0,
    val flags: Long = 0,
    val securityProtocol: Long = 0,
    val routedDvcCount: Long = 0,
    val failure: RdpTransportFailure = RdpTransportFailure.NONE,
    val tunnelHresult: Long = 0,
    val socketError: Long = 0,
    val reliable: RdpUdpTunnelSnapshot = RdpUdpTunnelSnapshot(),
    val lossy: RdpUdpTunnelSnapshot = RdpUdpTunnelSnapshot(),
) {
    val udpActive: Boolean get() = knownVersion && phase == RdpTransportPhase.ACTIVE && activeMask != 0L
    val downgradedToTcp: Boolean get() = flags and FLAG_DOWNGRADED_TO_TCP != 0L
    val reconnectRequested: Boolean get() = flags and FLAG_RECONNECT_REQUESTED != 0L

    companion object {
        const val FIELD_COUNT = 36
        const val VERSION = 1L
        const val MASK_UDP_R = 1L
        const val MASK_UDP_L = 2L
        const val MASK_UDP2 = 4L
        const val FLAG_DOWNGRADED_TO_TCP = 1L
        const val FLAG_RECONNECT_REQUESTED = 2L

        fun decode(raw: LongArray?): RdpTransportSnapshot {
            if (raw == null || raw.size != FIELD_COUNT || raw[0] != VERSION) return RdpTransportSnapshot()
            return RdpTransportSnapshot(
                knownVersion = true,
                phase = RdpTransportPhase.fromWire(raw[1]),
                requestedMask = raw[2],
                readyMask = raw[3],
                activeMask = raw[4],
                softSyncMask = raw[5],
                flags = raw[6],
                securityProtocol = raw[7],
                routedDvcCount = raw[8],
                failure = RdpTransportFailure.fromWire(raw[FAILURE_OFFSET]),
                tunnelHresult = raw[10],
                socketError = raw[11],
                reliable = decodeTunnel(raw, RELIABLE_TUNNEL_OFFSET),
                lossy = decodeTunnel(raw, LOSSY_TUNNEL_OFFSET),
            )
        }

        private fun decodeTunnel(raw: LongArray, offset: Int) = RdpUdpTunnelSnapshot(
            kind = RdpUdpKind.fromMask(raw[offset]),
            state = RdpUdpTunnelState.fromWire(raw[offset + 1]),
            protocolVersion = raw[offset + 2],
            receivedBytes = raw[offset + 3],
            sentBytes = raw[offset + 4],
            receivedPackets = raw[offset + 5],
            sentPackets = raw[offset + 6],
            retransmits = raw[offset + 7],
            fecRecovered = raw[offset + 8],
            droppedPackets = raw[offset + 9],
            smoothedRttMicros = raw[offset + 10],
            idleMillis = raw[offset + 11],
        )

        private const val FAILURE_OFFSET = 9
        private const val RELIABLE_TUNNEL_OFFSET = 12
        private const val LOSSY_TUNNEL_OFFSET = 24
    }
}
