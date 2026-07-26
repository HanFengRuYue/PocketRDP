package com.hanfengruyue.pocketrdp.feature.session.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
