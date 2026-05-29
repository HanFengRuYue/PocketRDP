package com.hanfengruyue.pocketrdp.feature.session.input

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Soft-keyboard bridge: an invisible BasicTextField that accepts IME input and forwards each
 * codepoint as a one-shot Unicode key event to the RDP server.
 *
 * Why not just `WindowInsetsController.show(ime())` and listen for KeyEvents? Because the
 * Android IME on most devices does NOT emit `KEYCODE_*` for letters/numbers/symbols — it
 * calls `InputConnection.commitText()` directly, which we can only intercept by hosting a
 * real text-editing widget. The hidden BasicTextField is that widget.
 *
 * Two paths feed into the RDP wire protocol:
 *
 * 1. **Unicode path (default for text)** — IME commits a character via `onValueChange`. We
 *    diff against the previous buffer, then for each new codepoint call `sendUnicode(cp, true)`
 *    and `sendUnicode(cp, false)`. This carries Chinese/Emoji/diacritics correctly.
 *
 * 2. **VK path** — Physical keyboards (or IMEs that emit KEYCODE for non-letter keys like
 *    Backspace / Enter / arrows / function keys) raise `onPreviewKeyEvent`. We map the
 *    Android keycode via [ScancodeMap.vkFor] and call `sendKey(vk, down)`. This keeps modifier
 *    semantics intact — e.g. sticky Ctrl + physical 'A' → Ctrl+A on the remote.
 *
 * **Buffer sentinel**: the field's value is reset to a zero-width space after each commit so
 * the buffer never grows (which would cause Compose to thrash recomposing a 10 MB string).
 * The leading sentinel also keeps the selection inside non-empty text so the IME's backspace
 * behaviour is predictable across vendors.
 */
private const val SENTINEL = "​" // zero-width space

@Composable
fun SessionImeBridge(
    visible: Boolean,
    onUnicodeText: (String) -> Unit,
    onVkKey: (vk: Int, down: Boolean) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var buffer by remember { mutableStateOf(TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))) }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    BasicTextField(
        value = buffer,
        onValueChange = { new ->
            // Diff: anything beyond the sentinel is fresh input. We don't try to interpret
            // the composition (intermediate IME state); each invocation sends only the delta
            // since the last reset, then snaps back to the sentinel so the next call sees a
            // clean baseline.
            val text = new.text
            if (text.length > SENTINEL.length) {
                val fresh = text.substring(SENTINEL.length)
                // Strip any stray sentinel characters the IME may have copied into composition.
                val clean = fresh.replace(SENTINEL, "")
                if (clean.isNotEmpty()) onUnicodeText(clean)
            }
            buffer = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { ev ->
                val nativeEv = ev.nativeKeyEvent
                val vk = ScancodeMap.vkFor(nativeEv.keyCode) ?: return@onPreviewKeyEvent false
                when (ev.type) {
                    KeyEventType.KeyDown -> onVkKey(vk, true)
                    KeyEventType.KeyUp -> onVkKey(vk, false)
                    else -> return@onPreviewKeyEvent false
                }
                true // consume so the TextField doesn't also try to edit on these keys
            },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
    )
}
