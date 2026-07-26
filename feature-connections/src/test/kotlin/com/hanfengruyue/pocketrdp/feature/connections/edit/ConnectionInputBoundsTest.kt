package com.hanfengruyue.pocketrdp.feature.connections.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionInputBoundsTest {
    @Test
    fun capDoesNotSplitASurrogatePair() {
        assertEquals("ab", "ab\uD83D\uDE00c".boundedUtf16(3))
        assertEquals("ab\uD83D\uDE00", "ab\uD83D\uDE00c".boundedUtf16(4))
    }
}
