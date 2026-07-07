package com.hanfengruyue.pocketrdp.feature.connections.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionThumbnailChoiceTest {
    @Test
    fun liveThumbnailWinsOverDiskThumbnail() {
        assertEquals("live", chooseConnectionThumbnail(liveThumbnail = "live", diskThumbnail = "disk"))
    }

    @Test
    fun diskThumbnailIsFallback() {
        assertEquals("disk", chooseConnectionThumbnail(liveThumbnail = null, diskThumbnail = "disk"))
    }

    @Test
    fun missingThumbnailsStayMissing() {
        assertNull(chooseConnectionThumbnail<String>(liveThumbnail = null, diskThumbnail = null))
    }
}
