package com.hanfengruyue.pocketrdp.feature.connections.list

import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionListItemTest {
    @Test
    fun projectsConnectionEntityWithoutCredentialPayload() {
        val entity = ConnectionEntity(
            id = 7L,
            name = "Office PC",
            host = "192.0.2.20",
            port = 3390,
            username = "alice",
            passwordCipher = byteArrayOf(1, 2, 3),
            passwordIv = byteArrayOf(4, 5, 6),
            lastUsedAt = 1_700_000_000_000L,
        )

        val projected = entity.toListItem()

        assertEquals(7L, projected.id)
        assertEquals("Office PC", projected.name)
        assertEquals("192.0.2.20", projected.host)
        assertEquals(3390, projected.port)
        assertEquals("alice", projected.username)
        assertEquals(1_700_000_000_000L, projected.lastUsedAt)
    }
}
