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
    TCP(PHASE_WIRE_TCP),
    NEGOTIATING(PHASE_WIRE_NEGOTIATING),
    WAITING_SOFT_SYNC(PHASE_WIRE_WAITING_SOFT_SYNC),
    ACTIVE(PHASE_WIRE_ACTIVE),
    UDP_FAILED(PHASE_WIRE_UDP_FAILED),
    RECONNECTING_TCP(PHASE_WIRE_RECONNECTING_TCP),
    UNKNOWN(WIRE_UNKNOWN),
    ;

    companion object {
        fun fromWire(value: Long): RdpTransportPhase = when (value) {
            PHASE_WIRE_TCP -> TCP
            PHASE_WIRE_NEGOTIATING -> NEGOTIATING
            PHASE_WIRE_WAITING_SOFT_SYNC -> WAITING_SOFT_SYNC
            PHASE_WIRE_ACTIVE -> ACTIVE
            PHASE_WIRE_UDP_FAILED -> UDP_FAILED
            PHASE_WIRE_RECONNECTING_TCP -> RECONNECTING_TCP
            else -> UNKNOWN
        }
    }
}

enum class RdpUdpKind(val mask: Long) {
    UDP_R(UDP_KIND_MASK_UDP_R),
    UDP_L(UDP_KIND_MASK_UDP_L),
    UDP2(UDP_KIND_MASK_UDP2),
    UNKNOWN(UDP_KIND_MASK_UNKNOWN),
    ;

    companion object {
        fun fromMask(mask: Long): RdpUdpKind = when (mask) {
            UDP_KIND_MASK_UDP_R -> UDP_R
            UDP_KIND_MASK_UDP_L -> UDP_L
            UDP_KIND_MASK_UDP2 -> UDP2
            else -> UNKNOWN
        }
    }
}

enum class RdpUdpTunnelState(val wireValue: Long) {
    IDLE(TUNNEL_STATE_WIRE_IDLE),
    SYN_SENT(TUNNEL_STATE_WIRE_SYN_SENT),
    SECURITY_HANDSHAKE(TUNNEL_STATE_WIRE_SECURITY_HANDSHAKE),
    RDPEMT_CREATE(TUNNEL_STATE_WIRE_RDPEMT_CREATE),
    READY(TUNNEL_STATE_WIRE_READY),
    ACTIVE(TUNNEL_STATE_WIRE_ACTIVE),
    FAILED(TUNNEL_STATE_WIRE_FAILED),
    CLOSED(TUNNEL_STATE_WIRE_CLOSED),
    UNKNOWN(WIRE_UNKNOWN),
    ;

    companion object {
        fun fromWire(value: Long): RdpUdpTunnelState = when (value) {
            TUNNEL_STATE_WIRE_IDLE -> IDLE
            TUNNEL_STATE_WIRE_SYN_SENT -> SYN_SENT
            TUNNEL_STATE_WIRE_SECURITY_HANDSHAKE -> SECURITY_HANDSHAKE
            TUNNEL_STATE_WIRE_RDPEMT_CREATE -> RDPEMT_CREATE
            TUNNEL_STATE_WIRE_READY -> READY
            TUNNEL_STATE_WIRE_ACTIVE -> ACTIVE
            TUNNEL_STATE_WIRE_FAILED -> FAILED
            TUNNEL_STATE_WIRE_CLOSED -> CLOSED
            else -> UNKNOWN
        }
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
        private const val MAX_CORE_WIRE = 16L
        private const val MIN_RUNTIME_WIRE = 101L
        private const val MAX_RUNTIME_WIRE = 104L

        private val BY_CORE_WIRE = arrayOf(
            NONE,
            UNSUPPORTED_PROTOCOL,
            INVALID_ENDPOINT,
            SOCKET_CONNECT,
            SYN_SEND,
            SYN_TIMEOUT,
            SYN_RECEIVE,
            SYN_PARSE,
            RESOURCE_LIMIT,
            FINAL_ACK,
            UNSUPPORTED_VERSION,
            SECURITY_HANDSHAKE,
            RDPEMT_REQUEST,
            RDPEMT_RESPONSE,
            RDPEMT_REJECTED,
            TRANSPORT_TIMEOUT,
            UNSUPPORTED_ROUTE,
        )

        private val BY_RUNTIME_WIRE = arrayOf(
            INVALID_DVC_ROUTE,
            DVC_DELIVERY,
            TUNNEL_POLL,
            TUNNEL_WRITE,
        )

        fun fromWire(value: Long): RdpTransportFailure = when (value) {
            in 0L..MAX_CORE_WIRE -> BY_CORE_WIRE[value.toInt()]
            in MIN_RUNTIME_WIRE..MAX_RUNTIME_WIRE -> BY_RUNTIME_WIRE[(value - MIN_RUNTIME_WIRE).toInt()]
            else -> UNKNOWN
        }
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

private const val WIRE_UNKNOWN = -1L

private const val PHASE_WIRE_TCP = 0L
private const val PHASE_WIRE_NEGOTIATING = 1L
private const val PHASE_WIRE_WAITING_SOFT_SYNC = 2L
private const val PHASE_WIRE_ACTIVE = 3L
private const val PHASE_WIRE_UDP_FAILED = 4L
private const val PHASE_WIRE_RECONNECTING_TCP = 5L

private const val UDP_KIND_MASK_UNKNOWN = 0L
private const val UDP_KIND_MASK_UDP_R = 1L
private const val UDP_KIND_MASK_UDP_L = 2L
private const val UDP_KIND_MASK_UDP2 = 4L

private const val TUNNEL_STATE_WIRE_IDLE = 0L
private const val TUNNEL_STATE_WIRE_SYN_SENT = 1L
private const val TUNNEL_STATE_WIRE_SECURITY_HANDSHAKE = 2L
private const val TUNNEL_STATE_WIRE_RDPEMT_CREATE = 3L
private const val TUNNEL_STATE_WIRE_READY = 4L
private const val TUNNEL_STATE_WIRE_ACTIVE = 5L
private const val TUNNEL_STATE_WIRE_FAILED = 6L
private const val TUNNEL_STATE_WIRE_CLOSED = 7L
