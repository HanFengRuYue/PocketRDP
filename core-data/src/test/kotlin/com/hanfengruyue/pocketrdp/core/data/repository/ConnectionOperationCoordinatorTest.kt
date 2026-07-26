package com.hanfengruyue.pocketrdp.core.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionOperationCoordinatorTest {
    @Test
    fun exclusiveOperationsDoNotOverlap() = runBlocking {
        val coordinator = ConnectionOperationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = launch(Dispatchers.Default) {
            coordinator.withExclusiveOperation {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = launch(Dispatchers.Default) {
            coordinator.withExclusiveOperation {
                order += "second"
            }
        }

        releaseFirst.complete(Unit)
        joinAll(first, second)

        assertEquals(listOf("first-start", "first-end", "second"), order)
    }
}
