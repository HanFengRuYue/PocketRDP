package com.hanfengruyue.pocketrdp.feature.session

import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarChordCodecTest {
    @Test
    fun shiftWinS_roundTripsThroughPersistedId() {
        val mask = ScancodeMap.Modifier.SHIFT or ScancodeMap.Modifier.WIN

        val encoded = encodeToolbarChordId(mask, "key_s")

        assertEquals(
            EncodedToolbarChord(modifierMask = mask, keyId = "key_s"),
            decodeToolbarChordId(encoded),
        )
    }

    @Test
    fun malformedIds_areRejected() {
        assertNull(decodeToolbarChordId("key_s"))
        assertNull(decodeToolbarChordId("chord_0_key_s"))
        assertNull(decodeToolbarChordId("chord_12_s"))
        assertNull(decodeToolbarChordId("chord_x_key_s"))
    }
}
