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
 * Resolved from the native [RdpClient.transportInfo] bitfield after the RDP connection sequence
 * (and the lazy RDP-UDP multitransport bootstrap) finish.
 */
enum class RdpTransport {
    UNKNOWN,
    TCP,
    /** RDP-UDP multitransport negotiated (UDP-R/UDP-L) — used alongside TCP for the lossy path. */
    UDP,
}
