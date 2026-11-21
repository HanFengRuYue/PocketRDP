package com.hanfengruyue.pocketrdp.core.rdp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPromptGateTest {
    @Test
    fun submitUnblocksAwaiterAndReplacesStringBuilders() {
        val gate = CredentialPromptGate(timeoutMs = 5_000L)
        val deferred = gate.begin(inst = 42L)
        val received = AtomicReference<RdpCredentials?>()
        val done = CountDownLatch(1)

        val worker = Thread {
            received.set(gate.awaitResponse(inst = 42L, deferred = deferred))
            done.countDown()
        }.apply { start() }

        assertTrue(gate.submit(username = "alice", domain = "CORP", password = "secret"))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        worker.join(2_000L)

        val creds = received.get()
        assertNotNull(creds)
        assertEquals(RdpCredentials("alice", "CORP", "secret"), creds)

        val userBuf = StringBuilder("oldUser")
        val domainBuf = StringBuilder("OLD")
        val passBuf = StringBuilder("oldPass")
        applyCredentials(creds!!, userBuf, domainBuf, passBuf)

        assertEquals("alice", userBuf.toString())
        assertEquals("CORP", domainBuf.toString())
        assertEquals("secret", passBuf.toString())
    }

    @Test
    fun cancelUnblocksAwaiterWithNull() {
        val gate = CredentialPromptGate(timeoutMs = 5_000L)
        val deferred = gate.begin(inst = 7L)
        val received = AtomicReference<RdpCredentials?>(RdpCredentials("x", "y", "z"))
        val done = CountDownLatch(1)

        val worker = Thread {
            received.set(gate.awaitResponse(inst = 7L, deferred = deferred))
            done.countDown()
        }.apply { start() }

        assertTrue(gate.cancel())
        assertTrue(done.await(2, TimeUnit.SECONDS))
        worker.join(2_000L)

        assertNull(received.get())
        assertFalse(gate.submit("late", "", "ignored"))
    }

    @Test
    fun cancelWithSpecificInstanceOnlyCancelsMatchingInstance() {
        val gate = CredentialPromptGate(timeoutMs = 5_000L)
        val deferred = gate.begin(inst = 10L)

        assertFalse(gate.cancel(inst = 99L))
        assertFalse(deferred.isCompleted)
        assertTrue(gate.cancel(inst = 10L))
        assertTrue(deferred.isCompleted)
        assertNull(gate.awaitResponse(inst = 10L, deferred = deferred))
    }

    @Test
    fun timeoutReturnsNullAndClearsPendingPrompt() {
        val gate = CredentialPromptGate(timeoutMs = 50L)
        val deferred = gate.begin(inst = 15L)

        assertNull(gate.awaitResponse(inst = 15L, deferred = deferred))
        assertFalse(gate.submit("late", "", "ignored"))
    }

    @Test
    fun beginSupersedesPreviousPendingPrompt() {
        val gate = CredentialPromptGate(timeoutMs = 5_000L)
        val first = gate.begin(inst = 1L)
        val second = gate.begin(inst = 2L)

        assertTrue(first.isCompleted)
        assertNull(gate.awaitResponse(inst = 1L, deferred = first))
        assertTrue(gate.submit("bob", "LAB", "pw"))
        assertEquals(RdpCredentials("bob", "LAB", "pw"), gate.awaitResponse(inst = 2L, deferred = second))
    }
}
