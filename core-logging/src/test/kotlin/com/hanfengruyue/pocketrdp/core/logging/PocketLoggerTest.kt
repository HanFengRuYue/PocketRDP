package com.hanfengruyue.pocketrdp.core.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketLoggerTest {

    @Test
    fun sanitizeReturnsSameStringOnFastPathWithoutDelimiters() {
        val plain = "FreeRDP connected successfully"
        assertSame(plain, PocketLogger.sanitize(plain))
    }

    @Test
    fun sanitizeRedactsCliCredentialsHostsCookiesAndCertificates() {
        val raw = "/u:alice /p:secret /d:CORP /v:10.0.0.5 /drive:PocketRDP,/storage/emulated/0 " +
            "cookie=abc123 cert:deadbeef certificate=pemdata thumbprint=aabbcc token=xyz999"
        val redacted = PocketLogger.sanitize(raw)

        assertTrue("/u:<redacted>" in redacted)
        assertTrue("/p:<redacted>" in redacted)
        assertTrue("/d:<redacted>" in redacted)
        assertTrue("/v:<redacted>" in redacted)
        assertTrue("/drive:<redacted>" in redacted)
        assertTrue("cookie=<redacted>" in redacted)
        assertTrue("cert:<redacted>" in redacted)
        assertTrue("certificate=<redacted>" in redacted)
        assertTrue("thumbprint=<redacted>" in redacted)
        assertTrue("token=<redacted>" in redacted)
        assertTrue("secret" !in redacted)
        assertTrue("abc123" !in redacted)
        assertTrue("deadbeef" !in redacted)
        assertTrue("xyz999" !in redacted)
    }

    @Test
    fun logEntryBufferSkipsSnapshotWhenUnobservedAndHydratesOnSubscribe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val buffer = LogEntryBuffer(capacity = 3, scope = scope)
            buffer.append(sampleEntry("m1"))
            buffer.append(sampleEntry("m2"))
            buffer.append(sampleEntry("m3"))
            buffer.append(sampleEntry("m4"))

            // Unobserved: StateFlow does not allocate/publish snapshots until subscribed.
            assertTrue(buffer.entries.value.isEmpty())

            // Subscribing hydrates with the bounded ring (m2, m3, m4).
            val hydrated = withTimeout(TEST_TIMEOUT_MS) {
                buffer.entries.first { it.size == 3 }
            }
            assertEquals(listOf("m2", "m3", "m4"), hydrated.map { it.message })

            // Once all subscribers detach, StateFlow snapshot is released.
            val clearedAfterDetach = withTimeout(TEST_TIMEOUT_MS) {
                while (buffer.entries.value.isNotEmpty()) {
                    delay(POLL_DELAY_MS)
                }
                true
            }
            assertTrue(clearedAfterDetach)

            // Re-subscribing after another append hydrates the latest ring contents.
            buffer.append(sampleEntry("m5"))
            val rehydrated = withTimeout(TEST_TIMEOUT_MS) {
                buffer.entries.first { it.map(PocketLogger.Entry::message) == listOf("m3", "m4", "m5") }
            }
            assertEquals(3, rehydrated.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun logEntryBufferClearBumpsGenerationAndIsThreadSafe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val buffer = LogEntryBuffer(capacity = 16, scope = scope)
            val initialGen = buffer.append(sampleEntry("before"))
            val nextGen = buffer.clear()
            assertEquals(initialGen + 1L, nextGen)
            assertTrue(buffer.snapshotWithGeneration().second.isEmpty())

            val writers = (1..4).map { worker ->
                launch(Dispatchers.Default) {
                    repeat(WRITER_ITERATIONS) { idx ->
                        buffer.append(sampleEntry("w$worker-$idx"))
                    }
                }
            }
            writers.joinAll()

            val (gen, snap) = buffer.snapshotWithGeneration()
            assertEquals(nextGen, gen)
            assertEquals(16, snap.size)
            assertEquals(16, snap.map { it.id }.toSet().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun logEntryBufferDoesNotLoseEntriesAcrossRapidResubscribeChurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val buffer = LogEntryBuffer(capacity = 32, scope = scope)
            repeat(CHURN_CYCLES) { cycle ->
                val collector = launch(Dispatchers.Default) {
                    buffer.entries.collect {}
                }
                buffer.append(sampleEntry("churn-$cycle"))
                collector.cancel()
            }
            val finalEntry = "final-check"
            buffer.append(sampleEntry(finalEntry))
            val observed = withTimeout(TEST_TIMEOUT_MS) {
                buffer.entries.first { list -> list.any { it.message == finalEntry } }
            }
            assertEquals(finalEntry, observed.last().message)
        } finally {
            scope.cancel()
        }
    }

    private fun sampleEntry(message: String): PocketLogger.Entry = PocketLogger.Entry(
        timestampMillis = SAMPLE_TIMESTAMP_MS,
        level = PocketLogger.Level.INFO,
        tag = "Test",
        message = message,
    )

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
        const val POLL_DELAY_MS = 5L
        const val WRITER_ITERATIONS = 25
        const val CHURN_CYCLES = 20
        const val SAMPLE_TIMESTAMP_MS = 1_700_000_000_000L
    }
}
