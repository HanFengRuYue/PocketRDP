package com.hanfengruyue.pocketrdp.core.data.model

import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionUdpDefaultTest {
    @Test
    fun newConnectionsDoNotEnableUdpWithoutUserOptIn() {
        val connection = ConnectionEntity(name = "test", host = "192.0.2.1", username = "user")

        assertFalse(connection.useMultitransport)
    }
}
