package com.hanfengruyue.pocketrdp.feature.session.input

import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import kotlinx.coroutines.delay

/**
 * Encodes committed IME text into RDP key events, choosing the safe wire path per character.
 *
 * The bug this fixes: Android soft keyboards deliver almost everything (letters, digits, even
 * ASCII punctuation) through `InputConnection.commitText()` rather than `KEYCODE_*` events, so
 * the IME bridge used to forward *every* character as a unicode keyboard event. But unicode
 * keyboard input only works if the server advertised `INPUT_FLAG_UNICODE`. When it didn't,
 * FreeRDP's `input_send_unicode_keyboard_event` returns FALSE, and the Android client's event
 * loop (`android_process_event`) treats that as a fatal error and tears down the whole session.
 * Result: typing a single character instantly dropped the connection.
 *
 * Routing strategy:
 *  1. **Printable ASCII** (letters / digits / US-layout punctuation) → **scancode path** via
 *     [ScancodeMap.asciiVkFor]. `freerdp_input_send_keyboard_event` does NOT depend on unicode
 *     negotiation, so this always works and never triggers the disconnect. Shift is wrapped
 *     around the keypress for capitals and shifted symbols.
 *  2. **Everything else** (CJK, emoji, accented letters) → **unicode path**, but ONLY when the
 *     server actually supports it. Otherwise the character is dropped (and logged, throttled)
 *     instead of killing the session.
 *
 * Iteration is by Unicode code point so astral-plane characters (emoji, rare CJK) survive.
 */
object TextInputEncoder {

    private const val TAG = "TextInput"
    private const val DROP_LOG_LIMIT = 10
    // Async-typing pacing: after this many code points, pause briefly. A large paste otherwise fired
    // thousands of key events back-to-back, blocking the UI thread (ANR/闪退) and flooding the RDP input
    // channel in one burst (服务端/中间盒断连). ~16 chars / 8 ms ≈ 2000 chars/s — fast to type, gentle on
    // the channel. Only [typeThrottled] paces; [type] stays synchronous for any caller that needs it.
    private const val THROTTLE_BATCH = 16
    private const val THROTTLE_DELAY_MS = 8L
    private var droppedNonAsciiLogCount = 0

    /**
     * Synchronous variant: encode + send the whole string inline. Fine for short input; for large
     * pastes prefer [typeThrottled] off the main thread (see SessionViewModel.typeText).
     *
     * @param unicodeSupported snapshot of [RdpClient.isUnicodeInputSupported] taken once for the
     *   whole string — capability doesn't change mid-session, so we avoid a JNI hop per char.
     * @param sendKey scancode/VK path (Windows VK in, native re-translates to PS/2).
     * @param sendUnicode unicode keyboard path.
     */
    fun type(
        text: String,
        unicodeSupported: Boolean,
        sendKey: (vk: Int, down: Boolean) -> Unit,
        sendUnicode: (codePoint: Int, down: Boolean) -> Unit,
    ) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            sendCodePoint(cp, unicodeSupported, sendKey, sendUnicode)
        }
    }

    /**
     * Throttled, suspending variant for large input. Same per-character routing as [type] but pauses
     * every [THROTTLE_BATCH] code points so a big paste neither blocks the caller's thread nor floods
     * the RDP input channel. MUST be called from a coroutine (typically a single-consumer background
     * one so keystroke order is preserved across overlapping pastes) — see SessionViewModel.typeText.
     */
    suspend fun typeThrottled(
        text: String,
        unicodeSupported: Boolean,
        sendKey: (vk: Int, down: Boolean) -> Unit,
        sendUnicode: (codePoint: Int, down: Boolean) -> Unit,
    ) {
        var i = 0
        var sent = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            sendCodePoint(cp, unicodeSupported, sendKey, sendUnicode)
            if (++sent % THROTTLE_BATCH == 0) delay(THROTTLE_DELAY_MS)
        }
    }

    /** Route one code point to the scancode (printable ASCII) or unicode path; drop+log otherwise. */
    private fun sendCodePoint(
        cp: Int,
        unicodeSupported: Boolean,
        sendKey: (vk: Int, down: Boolean) -> Unit,
        sendUnicode: (codePoint: Int, down: Boolean) -> Unit,
    ) {
        val ascii = ScancodeMap.asciiVkFor(cp)
        when {
            ascii != null -> {
                val (vk, shift) = ascii
                if (shift) sendKey(ScancodeMap.VK.LSHIFT, true)
                sendKey(vk, true)
                sendKey(vk, false)
                if (shift) sendKey(ScancodeMap.VK.LSHIFT, false)
            }
            unicodeSupported -> {
                sendUnicode(cp, true)
                sendUnicode(cp, false)
            }
            else -> {
                if (droppedNonAsciiLogCount < DROP_LOG_LIMIT) {
                    droppedNonAsciiLogCount++
                    PocketLogger.w(
                        TAG,
                        "dropping non-ASCII input U+${cp.toString(16).uppercase()} — " +
                            "server did not negotiate unicode keyboard input",
                    )
                }
            }
        }
    }
}
