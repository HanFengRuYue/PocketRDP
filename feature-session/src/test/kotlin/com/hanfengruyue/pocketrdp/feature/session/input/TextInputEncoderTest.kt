package com.hanfengruyue.pocketrdp.feature.session.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputEncoderTest {
    @Test
    fun astralCharacterIsSentAsUtf16SurrogatePair() {
        val unicodeEvents = mutableListOf<Pair<Int, Boolean>>()

        TextInputEncoder.type(
            text = "\uD83D\uDE00",
            unicodeSupported = true,
            sendKey = { _, _ -> error("emoji must use the unicode path") },
            sendUnicode = { unit, down -> unicodeEvents += unit to down },
        )

        assertEquals(
            listOf(
                0xD83D to true,
                0xD83D to false,
                0xDE00 to true,
                0xDE00 to false,
            ),
            unicodeEvents,
        )
    }

    @Test
    fun printableAsciiUsesVirtualKeyPath() {
        val keyEvents = mutableListOf<Pair<Int, Boolean>>()

        TextInputEncoder.type(
            text = "A",
            unicodeSupported = true,
            sendKey = { key, down -> keyEvents += key to down },
            sendUnicode = { _, _ -> error("ASCII must use the virtual-key path") },
        )

        assertEquals(
            listOf(
                ScancodeMap.VK.LSHIFT to true,
                'A'.code to true,
                'A'.code to false,
                ScancodeMap.VK.LSHIFT to false,
            ),
            keyEvents,
        )
    }

    @Test
    fun lineFeedAndCarriageReturnSendSingleReturnVirtualKey() {
        for (newline in listOf("\n", "\r", "\r\n")) {
            val keyEvents = mutableListOf<Pair<Int, Boolean>>()

            TextInputEncoder.type(
                text = newline,
                unicodeSupported = true,
                sendKey = { key, down -> keyEvents += key to down },
                sendUnicode = { _, _ -> error("newlines must use VK.RETURN, not unicode") },
            )

            assertEquals(
                listOf(
                    ScancodeMap.VK.RETURN to true,
                    ScancodeMap.VK.RETURN to false,
                ),
                keyEvents,
            )
        }
    }
}
