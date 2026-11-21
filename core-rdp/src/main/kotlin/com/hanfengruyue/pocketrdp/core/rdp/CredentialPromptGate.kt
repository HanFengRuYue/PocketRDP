package com.hanfengruyue.pocketrdp.core.rdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates FreeRDP's synchronous `OnAuthenticate` worker callback with the async UI dialog.
 *
 * Callers must check instance ownership and register a pending prompt atomically against teardown
 * so a concurrent `disconnect()`/`closeNativeInstance()` always wakes any blocked worker thread.
 */
internal class CredentialPromptGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val lock = Any()
    private var pendingInst: Long = 0L
    private var pending: CompletableDeferred<RdpCredentials?>? = null

    fun begin(inst: Long): CompletableDeferred<RdpCredentials?> {
        val deferred = CompletableDeferred<RdpCredentials?>()
        val previous = synchronized(lock) {
            val old = pending
            pendingInst = inst
            pending = deferred
            old
        }
        previous?.complete(null)
        return deferred
    }

    fun submit(username: String, domain: String, password: String): Boolean {
        val deferred = synchronized(lock) {
            val current = pending
            pendingInst = 0L
            pending = null
            current
        }
        return deferred?.complete(RdpCredentials(username, domain, password)) == true
    }

    fun cancel(inst: Long? = null): Boolean {
        val deferred = synchronized(lock) {
            if (inst != null && pendingInst != inst) {
                return@synchronized null
            }
            val current = pending
            pendingInst = 0L
            pending = null
            current
        }
        return deferred?.complete(null) == true
    }

    fun awaitResponse(
        inst: Long,
        deferred: CompletableDeferred<RdpCredentials?>,
    ): RdpCredentials? {
        return try {
            runBlocking {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            synchronized(lock) {
                if (pending === deferred && pendingInst == inst) {
                    pendingInst = 0L
                    pending = null
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 180_000L
    }
}

internal data class RdpCredentials(
    val username: String,
    val domain: String,
    val password: String,
)

internal fun applyCredentials(
    credentials: RdpCredentials,
    username: StringBuilder,
    domain: StringBuilder,
    password: StringBuilder,
) {
    username.setLength(0)
    username.append(credentials.username)
    domain.setLength(0)
    domain.append(credentials.domain)
    password.setLength(0)
    password.append(credentials.password)
}
