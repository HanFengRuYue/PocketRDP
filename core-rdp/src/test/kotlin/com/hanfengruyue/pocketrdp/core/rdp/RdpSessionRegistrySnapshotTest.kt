package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpSessionRegistrySnapshotTest {
    private class FakeClientKey
    private data class FakePreview(val id: Int, val maxDimension: Int)

    @Test
    fun activeConnectionIdsIncludesOnlyRealConnectionIdsAndIsPrecomputed() {
        val snapshot = RdpSessionRegistrySnapshot(
            activeCount = 3,
            connectedCount = 1,
            connectingCount = 1,
            reconnectingCount = 1,
            sessions = listOf(
                RdpSessionInfo(
                    connectionId = 7L,
                    displayName = "NAS",
                    hostLabel = "10.0.0.2:3389",
                    state = RdpSessionRuntimeState.CONNECTED,
                ),
                RdpSessionInfo(
                    connectionId = 0L,
                    displayName = "",
                    hostLabel = "",
                    state = RdpSessionRuntimeState.CONNECTING,
                ),
                RdpSessionInfo(
                    connectionId = 9L,
                    displayName = "Desktop",
                    hostLabel = "10.0.0.3:3389",
                    state = RdpSessionRuntimeState.RECONNECTING,
                ),
            ),
        )

        assertEquals(setOf(7L, 9L), snapshot.activeConnectionIds)
        assertSame(snapshot.activeConnectionIds, snapshot.activeConnectionIds)
        assertTrue(snapshot.hasActiveSessions)
        assertTrue(RdpSessionRegistrySnapshot().activeConnectionIds.isEmpty())
    }

    @Test
    fun livePreviewCacheReusesBitmapWhenFrontSeqAndMaxDimensionUnchanged() {
        val cache = LivePreviewCache<FakeClientKey, FakePreview>()
        val client = FakeClientKey()
        var creations = 0

        val first = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 4L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }
        val second = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 4L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }

        assertEquals(1, creations)
        assertSame(first, second)
    }

    @Test
    fun livePreviewCacheInvalidatesOnFrontSeqOrMaxDimensionChange() {
        val cache = LivePreviewCache<FakeClientKey, FakePreview>()
        val client = FakeClientKey()
        var creations = 0

        val first = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 1L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }
        val nextFrame = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 2L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }
        val resized = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 2L,
            maxDimension = 480,
        ) { FakePreview(++creations, 480) }

        assertEquals(3, creations)
        assertNotSame(first, nextFrame)
        assertNotSame(nextFrame, resized)
        assertEquals(480, resized?.maxDimension)
    }

    @Test
    fun livePreviewCacheEvictsWhenUnpublishedRemovedOrInvalidated() {
        val lock = Any()
        val cache = LivePreviewCache<FakeClientKey, FakePreview>(lock)
        val client = FakeClientKey()
        var creations = 0

        val initial = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 5L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }
        assertEquals(1, creations)

        // Unpublished frame evicts the cached entry.
        assertNull(
            cache.getOrCreate(
                key = client,
                hasPublishedFrame = false,
                frontSeq = 5L,
                maxDimension = 960,
            ) { FakePreview(++creations, 960) },
        )
        val recreated = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 5L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }
        assertEquals(2, creations)
        assertNotSame(initial, recreated)

        // Explicit removal evicts the cached entry.
        synchronized(lock) { cache.removeLocked(client) }
        // Concurrent disconnect while scaling (isKeyValidLocked=false) must not retain entry.
        val transientPreview = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 6L,
            maxDimension = 960,
            isKeyValidLocked = { false },
        ) { FakePreview(++creations, 960) }
        val afterReconnect = cache.getOrCreate(
            key = client,
            hasPublishedFrame = true,
            frontSeq = 6L,
            maxDimension = 960,
        ) { FakePreview(++creations, 960) }

        assertEquals(4, creations)
        assertNotSame(transientPreview, afterReconnect)
    }
}
