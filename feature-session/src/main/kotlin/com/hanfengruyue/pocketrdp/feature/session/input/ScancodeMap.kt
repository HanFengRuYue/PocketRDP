package com.hanfengruyue.pocketrdp.feature.session.input

import android.view.KeyEvent

/**
 * Maps Android [KeyEvent] codes to Windows Virtual-Key codes (`VK_*` constants from winuser.h).
 *
 * Why VK and not PS/2 scancodes: FreeRDP's `freerdp_send_key_event` JNI entry point feeds its
 * argument through `GetVirtualScanCodeFromVirtualKeyCode(keycode, 4)` before sending it to the
 * server. If we pass scancodes directly, that translation runs twice and most keys end up
 * mapped to garbage (e.g. PS/2 scancode 0x01 for Esc would be interpreted as VK_LBUTTON,
 * scancode 0x38 for Alt as VK_8, etc.). Pass VK and the chain produces the right scancode.
 */
object ScancodeMap {

    /**
     * VK codes our toolbar sends. Constants below are pre-ORed with [KBDEXT] (0x0100) when the
     * key sits in the extended-scancode table (LWIN/arrows/INSERT/DELETE/RCTRL/RALT…). Without
     * that flag, FreeRDP's `GetVirtualScanCodeFromVirtualKeyCode` searches the wrong table and
     * returns VK_NONE → the key sends scancode 0 → server receives nothing.
     *
     * Names match winuser.h; the 0x100 OR is an implementation detail of the JNI bridge.
     */
    private const val KBDEXT = 0x0100

    object VK {
        // Non-extended.
        const val BACK = 0x08
        const val TAB = 0x09
        const val RETURN = 0x0D
        const val SHIFT = 0x10
        const val CONTROL = 0x11
        const val MENU = 0x12   // Alt
        const val CAPS = 0x14
        const val ESCAPE = 0x1B
        const val SPACE = 0x20
        // VK_OEM_* punctuation (US layout). Send as VK so the remote keyboard layout
        // produces the right glyph. Non-US layouts may interpret differently — for those
        // the IME unicode path (sendUnicodeKey) is the safer fallback.
        const val OEM_PLUS = 0xBB    // '+' / '='
        const val OEM_COMMA = 0xBC   // ','
        const val OEM_MINUS = 0xBD   // '-' / '_'
        const val OEM_PERIOD = 0xBE  // '.'
        const val OEM_SLASH = 0xBF   // '/' / '?'
        const val OEM_GRAVE = 0xC0   // '`' / '~'
        const val OEM_LBRACKET = 0xDB // '['
        const val OEM_BACKSLASH = 0xDC // '\'
        const val OEM_RBRACKET = 0xDD // ']'
        const val OEM_QUOTE = 0xDE   // '''
        const val OEM_SEMICOLON = 0xBA // ';'
        const val F1 = 0x70
        const val F2 = 0x71
        const val F3 = 0x72
        const val F4 = 0x73
        const val F5 = 0x74
        const val F6 = 0x75
        const val F7 = 0x76
        const val F8 = 0x77
        const val F9 = 0x78
        const val F10 = 0x79
        const val F11 = 0x7A
        const val F12 = 0x7B
        const val LSHIFT = 0xA0
        const val RSHIFT = 0xA1
        const val LCONTROL = 0xA2
        const val LMENU = 0xA4   // Left Alt

        // Extended (must carry KBDEXT or FreeRDP's reverse lookup misses them).
        const val PAGE_UP = 0x21 or KBDEXT
        const val PAGE_DOWN = 0x22 or KBDEXT
        const val END = 0x23 or KBDEXT
        const val HOME = 0x24 or KBDEXT
        const val LEFT = 0x25 or KBDEXT
        const val UP = 0x26 or KBDEXT
        const val RIGHT = 0x27 or KBDEXT
        const val DOWN = 0x28 or KBDEXT
        const val INSERT = 0x2D or KBDEXT
        const val DELETE = 0x2E or KBDEXT
        const val LWIN = 0x5B or KBDEXT
        const val RWIN = 0x5C or KBDEXT
        const val APPS = 0x5D or KBDEXT
        const val RCONTROL = 0xA3 or KBDEXT
        const val RMENU = 0xA5 or KBDEXT  // AltGr / Right Alt
    }

    private val codes: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_DEL to VK.BACK,
        KeyEvent.KEYCODE_FORWARD_DEL to VK.DELETE,
        KeyEvent.KEYCODE_ENTER to VK.RETURN,
        KeyEvent.KEYCODE_NUMPAD_ENTER to VK.RETURN,
        KeyEvent.KEYCODE_TAB to VK.TAB,
        KeyEvent.KEYCODE_ESCAPE to VK.ESCAPE,
        KeyEvent.KEYCODE_INSERT to VK.INSERT,
        KeyEvent.KEYCODE_SPACE to VK.SPACE,
        KeyEvent.KEYCODE_CAPS_LOCK to VK.CAPS,
        KeyEvent.KEYCODE_DPAD_UP to VK.UP,
        KeyEvent.KEYCODE_DPAD_DOWN to VK.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to VK.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to VK.RIGHT,
        KeyEvent.KEYCODE_HOME to VK.HOME,
        KeyEvent.KEYCODE_MOVE_END to VK.END,
        KeyEvent.KEYCODE_PAGE_UP to VK.PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN to VK.PAGE_DOWN,
        KeyEvent.KEYCODE_F1 to VK.F1,
        KeyEvent.KEYCODE_F2 to VK.F2,
        KeyEvent.KEYCODE_F3 to VK.F3,
        KeyEvent.KEYCODE_F4 to VK.F4,
        KeyEvent.KEYCODE_F5 to VK.F5,
        KeyEvent.KEYCODE_F6 to VK.F6,
        KeyEvent.KEYCODE_F7 to VK.F7,
        KeyEvent.KEYCODE_F8 to VK.F8,
        KeyEvent.KEYCODE_F9 to VK.F9,
        KeyEvent.KEYCODE_F10 to VK.F10,
        KeyEvent.KEYCODE_F11 to VK.F11,
        KeyEvent.KEYCODE_F12 to VK.F12,
        KeyEvent.KEYCODE_CTRL_LEFT to VK.LCONTROL,
        KeyEvent.KEYCODE_CTRL_RIGHT to VK.RCONTROL,
        KeyEvent.KEYCODE_ALT_LEFT to VK.LMENU,
        KeyEvent.KEYCODE_ALT_RIGHT to VK.RMENU,
        KeyEvent.KEYCODE_SHIFT_LEFT to VK.LSHIFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT to VK.RSHIFT,
        KeyEvent.KEYCODE_META_LEFT to VK.LWIN,
        KeyEvent.KEYCODE_META_RIGHT to VK.RWIN,
    )

    fun vkFor(keyCode: Int): Int? = codes[keyCode]

    // Windows VK codes that coincide with ASCII for letters/digits (VK_A==0x41, VK_0==0x30).
    private const val VK_0 = 0x30
    private const val VK_1 = 0x31
    private const val VK_2 = 0x32
    private const val VK_3 = 0x33
    private const val VK_4 = 0x34
    private const val VK_5 = 0x35
    private const val VK_6 = 0x36
    private const val VK_7 = 0x37
    private const val VK_8 = 0x38
    private const val VK_9 = 0x39
    private const val VK_A = 0x41

    private val lowerLetters = 'a'.code..'z'.code
    private val upperLetters = 'A'.code..'Z'.code
    private val digits = '0'.code..'9'.code

    /**
     * US-QWERTY map for printable-ASCII punctuation → (VK, needsShift). Letters/digits are
     * handled arithmetically in [asciiVkFor]; everything else lives here. Both the unshifted and
     * shifted glyph of each physical key are listed so e.g. '/' and '?' both resolve to VK_OEM_2.
     */
    private val shiftedPunctuation: Map<Int, Pair<Int, Boolean>> = mapOf(
        '`'.code to (VK.OEM_GRAVE to false),
        '~'.code to (VK.OEM_GRAVE to true),
        '-'.code to (VK.OEM_MINUS to false),
        '_'.code to (VK.OEM_MINUS to true),
        '='.code to (VK.OEM_PLUS to false),
        '+'.code to (VK.OEM_PLUS to true),
        '['.code to (VK.OEM_LBRACKET to false),
        '{'.code to (VK.OEM_LBRACKET to true),
        ']'.code to (VK.OEM_RBRACKET to false),
        '}'.code to (VK.OEM_RBRACKET to true),
        '\\'.code to (VK.OEM_BACKSLASH to false),
        '|'.code to (VK.OEM_BACKSLASH to true),
        ';'.code to (VK.OEM_SEMICOLON to false),
        ':'.code to (VK.OEM_SEMICOLON to true),
        '\''.code to (VK.OEM_QUOTE to false),
        '"'.code to (VK.OEM_QUOTE to true),
        ','.code to (VK.OEM_COMMA to false),
        '<'.code to (VK.OEM_COMMA to true),
        '.'.code to (VK.OEM_PERIOD to false),
        '>'.code to (VK.OEM_PERIOD to true),
        '/'.code to (VK.OEM_SLASH to false),
        '?'.code to (VK.OEM_SLASH to true),
        '!'.code to (VK_1 to true),
        '@'.code to (VK_2 to true),
        '#'.code to (VK_3 to true),
        '$'.code to (VK_4 to true),
        '%'.code to (VK_5 to true),
        '^'.code to (VK_6 to true),
        '&'.code to (VK_7 to true),
        '*'.code to (VK_8 to true),
        '('.code to (VK_9 to true),
        ')'.code to (VK_0 to true),
    )

    /**
     * Map a printable-ASCII codepoint to a (Windows VK, needsShift) pair for the US-QWERTY
     * layout, or null when it can't be produced by a single US-layout key (non-ASCII, control
     * char). [TextInputEncoder] uses this to route text through the **scancode** path
     * (freerdp_input_send_keyboard_event) instead of the unicode path.
     *
     * Why it matters: a unicode keyboard event sent to a server that did NOT advertise
     * INPUT_FLAG_UNICODE is rejected by FreeRDP, and the Android event loop treats that rejection
     * as fatal — tearing down the whole session ("type one char and the connection drops").
     * Scancode input has no such dependency, so all ASCII takes this path.
     */
    fun asciiVkFor(codePoint: Int): Pair<Int, Boolean>? = when (codePoint) {
        in lowerLetters -> (VK_A + (codePoint - 'a'.code)) to false
        in upperLetters -> (VK_A + (codePoint - 'A'.code)) to true
        in digits -> (VK_0 + (codePoint - '0'.code)) to false
        ' '.code -> VK.SPACE to false
        else -> shiftedPunctuation[codePoint]
    }

    /** Win/Ctrl/Alt/Shift bit flags maintained by the sticky toolbar buttons. */
    object Modifier {
        const val CTRL = 0x01
        const val ALT = 0x02
        const val SHIFT = 0x04
        const val WIN = 0x08

        fun vkFor(flag: Int): Int? = when (flag) {
            CTRL -> VK.LCONTROL
            ALT -> VK.LMENU
            SHIFT -> VK.LSHIFT
            WIN -> VK.LWIN
            else -> null
        }
    }
}
