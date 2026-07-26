package com.hanfengruyue.pocketrdp.feature.session.input

import android.view.KeyEvent
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Soft-keyboard bridge: an invisible BasicTextField that accepts committed IME text and forwards
 * it to [TextInputEncoder] through [onUnicodeText].
 *
 * Why not just `WindowInsetsController.show(ime())` and listen for KeyEvents? Because the
 * Android IME on most devices does NOT emit `KEYCODE_*` for letters/numbers/symbols — it
 * calls `InputConnection.commitText()` directly, which we can only intercept by hosting a
 * real text-editing widget. The hidden BasicTextField is that widget.
 *
 * Two paths feed into the RDP wire protocol:
 *
 * 1. **Committed-text path** — IME commits text via `onValueChange`. We diff against the sentinel
 *    buffer and pass the fresh text to [TextInputEncoder], which uses the scancode path for
 *    printable ASCII and the guarded Unicode path for CJK, emoji and other non-ASCII text.
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
private val RESET_VALUE = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))

internal data class ImeEditOutcome(
    val nextValue: TextFieldValue,
    val committedText: String = "",
    val backspace: Boolean = false,
)

internal fun processImeEdit(newValue: TextFieldValue): ImeEditOutcome {
    // setComposingText is provisional (pinyin, kana, handwriting, etc.). Forwarding it would type
    // every intermediate candidate. Preserve it until commitText clears the composition range.
    if (newValue.composition != null) return ImeEditOutcome(nextValue = newValue)

    if (newValue.text.isEmpty()) {
        return ImeEditOutcome(nextValue = RESET_VALUE, backspace = true)
    }

    val fresh = if (newValue.text.startsWith(SENTINEL)) {
        newValue.text.substring(SENTINEL.length)
    } else {
        // Some IMEs replace the selected sentinel instead of appending after it.
        newValue.text
    }
    val clean = fresh.replace(SENTINEL, "")
    return ImeEditOutcome(nextValue = RESET_VALUE, committedText = clean)
}

internal fun applyImeVisibility(
    visible: Boolean,
    requestFocus: () -> Unit,
    clearFocus: () -> Unit,
    showKeyboard: () -> Unit,
    hideKeyboard: () -> Unit,
) {
    if (visible) {
        requestFocus()
        showKeyboard()
    } else {
        clearFocus()
        hideKeyboard()
    }
}

@Composable
fun SessionImeBridge(
    visible: Boolean,
    onUnicodeText: (String) -> Unit,
    onVkKey: (vk: Int, down: Boolean) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var buffer by remember { mutableStateOf(RESET_VALUE) }

    LaunchedEffect(visible) {
        applyImeVisibility(
            visible = visible,
            requestFocus = focusRequester::requestFocus,
            clearFocus = { focusManager.clearFocus(force = true) },
            showKeyboard = { keyboard?.show() },
            hideKeyboard = { keyboard?.hide() },
        )
    }

    BasicTextField(
        value = buffer,
        onValueChange = { new ->
            val outcome = processImeEdit(new)
            if (outcome.backspace) {
                ScancodeMap.vkFor(KeyEvent.KEYCODE_DEL)?.let { backspaceVk ->
                    onVkKey(backspaceVk, true)
                    onVkKey(backspaceVk, false)
                }
            }
            if (outcome.committedText.isNotEmpty()) onUnicodeText(outcome.committedText)
            buffer = outcome.nextValue
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
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
    )
}
