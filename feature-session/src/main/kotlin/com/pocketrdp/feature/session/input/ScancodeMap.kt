package com.pocketrdp.feature.session.input

import android.view.KeyEvent

/**
 * Minimal Android KeyEvent → RDP scancode mapping for control keys. Printable characters go
 * through [RdpClient.sendUnicodeKey] instead so we don't need a full layout table here.
 */
object ScancodeMap {
    private val codes: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_DEL to 0x0E,            // Backspace
        KeyEvent.KEYCODE_FORWARD_DEL to 0x53,    // Delete
        KeyEvent.KEYCODE_ENTER to 0x1C,
        KeyEvent.KEYCODE_TAB to 0x0F,
        KeyEvent.KEYCODE_ESCAPE to 0x01,
        KeyEvent.KEYCODE_DPAD_UP to 0x48,
        KeyEvent.KEYCODE_DPAD_DOWN to 0x50,
        KeyEvent.KEYCODE_DPAD_LEFT to 0x4B,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0x4D,
        KeyEvent.KEYCODE_HOME to 0x47,
        KeyEvent.KEYCODE_MOVE_END to 0x4F,
        KeyEvent.KEYCODE_PAGE_UP to 0x49,
        KeyEvent.KEYCODE_PAGE_DOWN to 0x51,
        KeyEvent.KEYCODE_F1 to 0x3B,
        KeyEvent.KEYCODE_F2 to 0x3C,
        KeyEvent.KEYCODE_F3 to 0x3D,
        KeyEvent.KEYCODE_F4 to 0x3E,
        KeyEvent.KEYCODE_F5 to 0x3F,
        KeyEvent.KEYCODE_F6 to 0x40,
        KeyEvent.KEYCODE_F7 to 0x41,
        KeyEvent.KEYCODE_F8 to 0x42,
        KeyEvent.KEYCODE_F9 to 0x43,
        KeyEvent.KEYCODE_F10 to 0x44,
        KeyEvent.KEYCODE_F11 to 0x57,
        KeyEvent.KEYCODE_F12 to 0x58,
        KeyEvent.KEYCODE_CTRL_LEFT to 0x1D,
        KeyEvent.KEYCODE_ALT_LEFT to 0x38,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0x2A,
        KeyEvent.KEYCODE_META_LEFT to 0x5B,      // Win key
    )

    fun scancodeFor(keyCode: Int): Int? = codes[keyCode]

    /** Win/Ctrl/Alt/Shift bit flags maintained by the sticky toolbar buttons. */
    object Modifier {
        const val CTRL = 0x01
        const val ALT = 0x02
        const val SHIFT = 0x04
        const val WIN = 0x08
    }
}
