package com.pocketrdp.core.rdp

enum class InputMode {
    /** Direct touch mapping — single-finger taps treated as remote touch events. */
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
