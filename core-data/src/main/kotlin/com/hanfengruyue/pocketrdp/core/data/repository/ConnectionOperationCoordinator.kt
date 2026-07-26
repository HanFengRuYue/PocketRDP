package com.hanfengruyue.pocketrdp.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes connection launch preflight/registration against connection deletion.
 *
 * Room transactions cannot include the in-memory RDP session registry. Without this process-local
 * critical section, deletion can observe "inactive", suspend in Room, and then remove a row after a
 * session has already loaded its credentials but before that session registers as active.
 */
@Singleton
class ConnectionOperationCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveOperation(block: suspend () -> T): T =
        mutex.withLock { block() }
}
