package com.hanfengruyue.pocketrdp.feature.session.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
 * Soft-keyboard and physical-keyboard bridge.
 *
 * Why a hidden [BasicTextField] for the soft keyboard? Most Android IMEs do not emit `KEYCODE_*`
 * events for letters, digits, or CJK text — they call `InputConnection.commitText()`, which only a
 * real text-editing widget receives. When [visible] is false, focus moves off the text field (so
 * Android never restores the soft IME on resume) and onto a plain non-text focusable node that
 * captures physical Bluetooth/USB keyboard events without opening the soft keyboard.
 *
 * Two paths feed into the RDP wire protocol:
 *
 * 1. **Committed-text path** — IME commits text into `TextFieldState`. We diff against the sentinel
 *    buffer and pass the fresh text to [TextInputEncoder], which uses the scancode path for
 *    printable ASCII (including Enter) and the guarded Unicode path for CJK, emoji, and other
 *    non-ASCII text.
 *
 * 2. **VK path** — Physical keyboards (or IMEs that emit `KEYCODE_*` for special keys) raise
 *    `onPreviewKeyEvent`. Special keys and modifier shortcut chords (`Ctrl+C`, `Win+D`, `Alt+F4`)
 *    are mapped via [ScancodeMap] and forwarded directly as Windows Virtual-Key events so
 *    [BasicTextField] cannot swallow shortcuts.
 *
 * **Buffer sentinel**: the field's value is reset to a zero-width space after each commit so
 * the buffer never grows and backspace behavior stays predictable across IME vendors.
 */
private const val SENTINEL = "\u200B" // zero-width space
private val RESET_VALUE = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))
private const val SHORTCUT_STICKY_MODIFIER_MASK =
    ScancodeMap.Modifier.CTRL or ScancodeMap.Modifier.ALT or ScancodeMap.Modifier.WIN

internal data class ImeEditOutcome(
    val nextValue: TextFieldValue,
    val committedText: String = "",
    val backspace: Boolean = false,
)

internal data class HardwareKeyInput(
    val keyCode: Int,
    val isDown: Boolean,
    val isUp: Boolean,
    val isCtrlPressed: Boolean = false,
    val isAltPressed: Boolean = false,
    val isMetaPressed: Boolean = false,
    val unicodeChar: Int = 0,
    val stickyModifiers: Int = 0,
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

internal fun dispatchHardwareKeyEvent(
    input: HardwareKeyInput,
    allowPlainTextKeys: Boolean,
    pressedVks: MutableMap<Int, Int>,
    onVkKey: (vk: Int, down: Boolean) -> Unit,
    onUnicodeText: (String) -> Unit,
): Boolean {
    if (input.isDown) {
        return handleHardwareKeyDown(input, allowPlainTextKeys, pressedVks, onVkKey, onUnicodeText)
    }
    if (input.isUp) {
        return handleHardwareKeyUp(input, allowPlainTextKeys, pressedVks, onVkKey)
    }
    return false
}

private fun handleHardwareKeyDown(
    input: HardwareKeyInput,
    allowPlainTextKeys: Boolean,
    pressedVks: MutableMap<Int, Int>,
    onVkKey: (vk: Int, down: Boolean) -> Unit,
    onUnicodeText: (String) -> Unit,
): Boolean {
    val hasPhysicalShortcut = input.isCtrlPressed || input.isAltPressed || input.isMetaPressed
    val hasStickyShortcut = input.stickyModifiers and SHORTCUT_STICKY_MODIFIER_MASK != 0
    val hasShortcutModifier = hasPhysicalShortcut || hasStickyShortcut
    val vk = if (allowPlainTextKeys || hasShortcutModifier) {
        ScancodeMap.hardwareVkFor(input.keyCode)
    } else {
        ScancodeMap.vkFor(input.keyCode)
    }
    if (vk != null) {
        pressedVks[input.keyCode] = vk
        onVkKey(vk, true)
        return true
    }
    val canEmitUnicode = allowPlainTextKeys && !hasShortcutModifier && input.unicodeChar != 0
    val isPrintable = (input.unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
        !Character.isISOControl(input.unicodeChar)
    if (canEmitUnicode && isPrintable) {
        onUnicodeText(String(Character.toChars(input.unicodeChar)))
        return true
    }
    return false
}

private fun handleHardwareKeyUp(
    input: HardwareKeyInput,
    allowPlainTextKeys: Boolean,
    pressedVks: MutableMap<Int, Int>,
    onVkKey: (vk: Int, down: Boolean) -> Unit,
): Boolean {
    val trackedVk = pressedVks.remove(input.keyCode)
    val vk = trackedVk ?: if (allowPlainTextKeys) {
        ScancodeMap.hardwareVkFor(input.keyCode)
    } else {
        ScancodeMap.vkFor(input.keyCode)
    }
    if (vk != null) {
        onVkKey(vk, false)
        return true
    }
    return false
}

private fun androidx.compose.ui.input.key.KeyEvent.toHardwareKeyInput(
    stickyModifiers: Int,
): HardwareKeyInput {
    val nativeEv = nativeKeyEvent
    return HardwareKeyInput(
        keyCode = nativeEv.keyCode,
        isDown = type == KeyEventType.KeyDown,
        isUp = type == KeyEventType.KeyUp,
        isCtrlPressed = nativeEv.isCtrlPressed,
        isAltPressed = nativeEv.isAltPressed,
        isMetaPressed = nativeEv.isMetaPressed,
        unicodeChar = nativeEv.unicodeChar,
        stickyModifiers = stickyModifiers,
    )
}

@Composable
fun SessionImeBridge(
    visible: Boolean,
    onUnicodeText: (String) -> Unit,
    onVkKey: (vk: Int, down: Boolean) -> Unit,
    stickyModifiers: Int = 0,
    captureHardwareKeys: Boolean = true,
) {
    val imeFocusRequester = remember { FocusRequester() }
    val hardwareFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val textFieldState = rememberTextFieldState(initialText = SENTINEL)
    val pressedVks = remember { mutableMapOf<Int, Int>() }
    val currentOnUnicodeText = rememberUpdatedState(onUnicodeText)
    val currentOnVkKey = rememberUpdatedState(onVkKey)
    val currentStickyModifiers = rememberUpdatedState(stickyModifiers)

    LaunchedEffect(textFieldState) {
        snapshotFlow {
            TextFieldValue(
                text = textFieldState.text.toString(),
                selection = textFieldState.selection,
                composition = textFieldState.composition,
            )
        }.collect { current ->
            if (current == RESET_VALUE) return@collect
            val outcome = processImeEdit(current)
            if (outcome.backspace) {
                ScancodeMap.vkFor(KeyEvent.KEYCODE_DEL)?.let { backspaceVk ->
                    currentOnVkKey.value(backspaceVk, true)
                    currentOnVkKey.value(backspaceVk, false)
                }
            }
            if (outcome.committedText.isNotEmpty()) {
                currentOnUnicodeText.value(outcome.committedText)
            }
            if (outcome.nextValue == RESET_VALUE) {
                textFieldState.setTextAndPlaceCursorAtEnd(SENTINEL)
            }
        }
    }

    LaunchedEffect(visible) {
        applyImeVisibility(
            visible = visible,
            requestFocus = { runCatching { imeFocusRequester.requestFocus() } },
            clearFocus = { focusManager.clearFocus(force = true) },
            showKeyboard = { keyboard?.show() },
            hideKeyboard = { keyboard?.hide() },
        )
    }

    LaunchedEffect(visible, captureHardwareKeys) {
        if (!visible && captureHardwareKeys) {
            runCatching { hardwareFocusRequester.requestFocus() }
        }
    }

    DisposableEffect(captureHardwareKeys) {
        onDispose {
            if (pressedVks.isNotEmpty()) {
                pressedVks.values.toList().forEach { vk -> currentOnVkKey.value(vk, false) }
                pressedVks.clear()
            }
        }
    }

    Box(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(hardwareFocusRequester)
            .focusable(enabled = captureHardwareKeys)
            .onPreviewKeyEvent { ev ->
                dispatchHardwareKeyEvent(
                    input = ev.toHardwareKeyInput(currentStickyModifiers.value),
                    allowPlainTextKeys = true,
                    pressedVks = pressedVks,
                    onVkKey = currentOnVkKey.value,
                    onUnicodeText = currentOnUnicodeText.value,
                )
            },
    )

    BasicTextField(
        state = textFieldState,
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(imeFocusRequester)
            .onPreviewKeyEvent { ev ->
                dispatchHardwareKeyEvent(
                    input = ev.toHardwareKeyInput(currentStickyModifiers.value),
                    allowPlainTextKeys = false,
                    pressedVks = pressedVks,
                    onVkKey = currentOnVkKey.value,
                    onUnicodeText = currentOnUnicodeText.value,
                )
            },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
    )
}
