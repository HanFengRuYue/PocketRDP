package com.hanfengruyue.pocketrdp.feature.session

import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun regularPickerHasNoDuplicateBackOrEnterKeys() {
        val ids = regularQuickPickerKeyIds()

        assertEquals(ids.distinct(), ids)
        assertTrue("key_back" in ids)
        assertTrue("key_enter" in ids)
        assertTrue("key_del" in ids)
    }

    @Test
    fun chordBuilderCoversAllFiveSectionsWithoutDuplicates() {
        val ids = chordBuilderKeyIds()

        assertEquals(ids.distinct(), ids)
        assertTrue("key_a" in ids)
        assertTrue("key_0" in ids)
        assertTrue("key_back" in ids)
        assertTrue("key_enter" in ids)
        assertTrue("key_left" in ids)
        assertTrue("key_home" in ids)
        assertTrue("key_del" in ids)
        assertTrue("key_f4" in ids)
        assertTrue("key_f12" in ids)
        assertTrue(isKnownToolbarChordId(encodeToolbarChordId(ScancodeMap.Modifier.ALT, "key_f4")))
        assertTrue(isKnownToolbarChordId(encodeToolbarChordId(ScancodeMap.Modifier.WIN, "key_left")))
        assertTrue(isKnownToolbarChordId(encodeToolbarChordId(ScancodeMap.Modifier.CTRL, "key_back")))
    }
}
