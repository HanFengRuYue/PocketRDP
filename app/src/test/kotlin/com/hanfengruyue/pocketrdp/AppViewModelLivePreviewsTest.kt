package com.hanfengruyue.pocketrdp

import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionInfo
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistrySnapshot
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRuntimeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppViewModelLivePreviewsTest {

    @Test
    fun idleOrConnectingSnapshotsDoNotPollCapture() = runBlocking {
        val snapshot = MutableStateFlow(
            RdpSessionRegistrySnapshot(
                activeCount = 1,
                connectedCount = 0,
                connectingCount = 1,
                sessions = listOf(
                    RdpSessionInfo(connectionId = 1L, state = RdpSessionRuntimeState.CONNECTING),
                ),
            ),
        )
        val captureCalls = AtomicInteger(0)
        val emissions = mutableListOf<Map<Long, String>>()
        val job = launch {
            livePreviewsFlow(
                snapshot = snapshot,
                intervalMs = POLL_INTERVAL_MS,
                capture = {
                    captureCalls.incrementAndGet()
                    mapOf(1L to "frame")
                },
            ).collect { emissions += it }
        }

        delay(IDLE_WAIT_MS)
        assertEquals(0, captureCalls.get())
        assertEquals(listOf(emptyMap<Long, String>()), emissions)
        job.cancel()
    }

    @Test
    fun startsPollingOnConnectedAndStopsWhenDisconnected() = runBlocking {
        val snapshot = MutableStateFlow(RdpSessionRegistrySnapshot())
        val captureCalls = AtomicInteger(0)
        val latest = MutableStateFlow<Map<Long, String>>(emptyMap())
        val job = launch {
            livePreviewsFlow(
                snapshot = snapshot,
                intervalMs = POLL_INTERVAL_MS,
                capture = { mapOf(1L to "frame-${captureCalls.incrementAndGet()}") },
            ).collect { latest.value = it }
        }

        delay(IDLE_WAIT_MS)
        assertEquals(0, captureCalls.get())

        snapshot.value = RdpSessionRegistrySnapshot(
            activeCount = 1,
            connectedCount = 1,
            sessions = listOf(
                RdpSessionInfo(connectionId = 1L, state = RdpSessionRuntimeState.CONNECTED),
            ),
        )
        withTimeout(TEST_TIMEOUT_MS) {
            while (latest.value[1L] == null) {
                delay(SHORT_DELAY_MS)
            }
        }
        assertTrue(captureCalls.get() >= 1)

        snapshot.value = RdpSessionRegistrySnapshot()
        withTimeout(TEST_TIMEOUT_MS) {
            while (latest.value.isNotEmpty()) {
                delay(SHORT_DELAY_MS)
            }
        }
        val callsAfterStop = captureCalls.get()
        delay(IDLE_WAIT_MS)
        assertEquals(callsAfterStop, captureCalls.get())
        job.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 20L
        const val SHORT_DELAY_MS = 5L
        const val IDLE_WAIT_MS = 80L
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
