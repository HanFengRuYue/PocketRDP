package com.hanfengruyue.pocketrdp.feature.session.input

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionImeBridgeTest {
    @Test
    fun composingTextIsNotForwarded() {
        val composing = TextFieldValue(
            text = "\u200Bni",
            selection = TextRange(3),
            composition = TextRange(1, 3),
        )

        val outcome = processImeEdit(composing)

        assertEquals(composing, outcome.nextValue)
        assertEquals("", outcome.committedText)
        assertFalse(outcome.backspace)
    }

    @Test
    fun committedCandidateIsForwardedOnce() {
        val outcome = processImeEdit(
            TextFieldValue(text = "\u200B你", selection = TextRange(2)),
        )

        assertEquals("你", outcome.committedText)
        assertEquals("\u200B", outcome.nextValue.text)
        assertFalse(outcome.backspace)
    }

    @Test
    fun imeMayReplaceSentinelOnCommit() {
        val outcome = processImeEdit(
            TextFieldValue(text = "😀", selection = TextRange(2)),
        )

        assertEquals("😀", outcome.committedText)
    }

    @Test
    fun deletingSentinelBecomesBackspace() {
        val outcome = processImeEdit(TextFieldValue(""))

        assertTrue(outcome.backspace)
        assertEquals("", outcome.committedText)
        assertEquals("\u200B", outcome.nextValue.text)
    }

    @Test
    fun scancodeMapMapsKeyboardHomeAndIgnoresLauncherHome() {
        assertEquals(ScancodeMap.VK.HOME, ScancodeMap.vkFor(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(ScancodeMap.VK.HOME, ScancodeMap.hardwareVkFor(KeyEvent.KEYCODE_MOVE_HOME))
        assertNull(ScancodeMap.vkFor(KeyEvent.KEYCODE_HOME))
        assertNull(ScancodeMap.hardwareVkFor(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun hardwareModifierChordsBypassTextFieldAndReleaseEvenIfModifierLiftsFirst() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val pressed = mutableMapOf<Int, Int>()

        val plainHandled = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(keyCode = KeyEvent.KEYCODE_C, isDown = true, isUp = false),
            allowPlainTextKeys = false,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )
        assertFalse(plainHandled)
        assertTrue(sent.isEmpty())

        val ctrlCDown = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(
                keyCode = KeyEvent.KEYCODE_C,
                isDown = true,
                isUp = false,
                isCtrlPressed = true,
            ),
            allowPlainTextKeys = false,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )
        val cUpAfterCtrlReleased = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(
                keyCode = KeyEvent.KEYCODE_C,
                isDown = false,
                isUp = true,
                isCtrlPressed = false,
            ),
            allowPlainTextKeys = false,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )

        assertTrue(ctrlCDown)
        assertTrue(cUpAfterCtrlReleased)
        assertEquals(listOf('C'.code to true, 'C'.code to false), sent)
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun hardwareKeyboardWorksWhenSoftImeHiddenAndPreservesAndroidSystemKeys() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val pressed = mutableMapOf<Int, Int>()

        val letterDown = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(keyCode = KeyEvent.KEYCODE_D, isDown = true, isUp = false),
            allowPlainTextKeys = true,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )
        val letterUp = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(keyCode = KeyEvent.KEYCODE_D, isDown = false, isUp = true),
            allowPlainTextKeys = true,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )
        val backHandled = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(keyCode = KeyEvent.KEYCODE_BACK, isDown = true, isUp = false),
            allowPlainTextKeys = true,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )
        val volumeHandled = dispatchHardwareKeyEvent(
            input = HardwareKeyInput(keyCode = KeyEvent.KEYCODE_VOLUME_UP, isDown = true, isUp = false),
            allowPlainTextKeys = true,
            pressedVks = pressed,
            onVkKey = { vk, down -> sent += vk to down },
            onUnicodeText = {},
        )

        assertTrue(letterDown)
        assertTrue(letterUp)
        assertFalse(backHandled)
        assertFalse(volumeHandled)
        assertEquals(listOf('D'.code to true, 'D'.code to false), sent)
    }
}
