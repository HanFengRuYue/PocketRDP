package com.hanfengruyue.pocketrdp.core.rdp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapBufferFramesTest {
    private data class FakeFrame(val width: Int, val height: Int, val tag: String)
    private data class FakeRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun createBuffer(clock: () -> Long = { 100L }): DoubleBufferedFrames<FakeFrame, FakeRect> {
        var nextId = 0
        return DoubleBufferedFrames(
            allocate = { w, h -> FakeFrame(w, h, "alloc-${++nextId}") },
            scaleCopy = { src, w, h -> FakeFrame(w, h, "copy-of-${src.tag}") },
            widthOf = FakeFrame::width,
            heightOf = FakeFrame::height,
            makeRect = ::FakeRect,
            nowMillis = clock,
        )
    }

    @Test
    fun snapshotReturnsNullBeforeResizeAndBeforeFirstPublish() {
        val frames = createBuffer()

        assertFalse(frames.hasPublishedFrame.value)
        assertNull(frames.snapshot(960))

        frames.resize(1920, 1080)

        assertNotNull(frames.peekFront())
        assertFalse(frames.hasPublishedFrame.value)
        assertNull(frames.snapshot(960))
    }

    @Test
    fun snapshotReturnsDownscaledCopyAfterCommitWithoutUpscaling() {
        val frames = createBuffer(clock = { 1234L })
        frames.resize(1920, 1080)
        val backBeforeCommit = frames.nativeBuffer()

        frames.commitFrame(10, 20, 300, 200)

        assertTrue(frames.hasPublishedFrame.value)
        assertSame(backBeforeCommit, frames.peekFront())
        assertEquals(1L, frames.peekFrontSeq())
        assertEquals(1234L, frames.peekFrontCommitMs())
        assertEquals(FakeRect(10, 20, 310, 220), frames.staleRect())

        val downscaled = frames.snapshot(960)
        assertNotNull(downscaled)
        assertEquals(960, downscaled!!.width)
        assertEquals(540, downscaled.height)

        val unscaled = frames.snapshot(4000)
        assertNotNull(unscaled)
        assertEquals(1920, unscaled!!.width)
        assertEquals(1080, unscaled.height)

        assertNull(frames.snapshot(0))
    }

    @Test
    fun resizeToNewDimensionsBlocksSnapshotUntilNextCommit() {
        val frames = createBuffer()
        frames.resize(1920, 1080)
        frames.commitFrame(0, 0, 1920, 1080)
        assertNotNull(frames.snapshot(960))

        frames.resize(1280, 720)

        assertFalse(frames.hasPublishedFrame.value)
        assertNull(frames.staleRect())
        assertNull(frames.snapshot(960))

        frames.commitFrame(0, 0, 1280, 720)

        assertTrue(frames.hasPublishedFrame.value)
        val snap = frames.snapshot(640)
        assertNotNull(snap)
        assertEquals(640, snap!!.width)
        assertEquals(360, snap.height)
    }

    @Test
    fun sameSizeResizePreservesPublishedFrameState() {
        val frames = createBuffer()
        frames.resize(1920, 1080)
        frames.commitFrame(0, 0, 1920, 1080)
        val existingFront = frames.peekFront()

        val returned = frames.resize(1920, 1080)

        assertSame(existingFront, returned)
        assertTrue(frames.hasPublishedFrame.value)
        assertNotNull(frames.snapshot(960))
    }

    @Test
    fun releaseClearsFramesAndBlocksSnapshot() {
        val frames = createBuffer()
        frames.resize(1920, 1080)
        frames.commitFrame(0, 0, 1920, 1080)

        frames.release()

        assertNull(frames.peekFront())
        assertNull(frames.nativeBuffer())
        assertNull(frames.staleRect())
        assertFalse(frames.hasPublishedFrame.value)
        assertNull(frames.snapshot(960))
    }

    @Test
    fun updateBackBufferResyncsUncoveredStaleRectAndSkipsCoveredStaleRect() {
        val frames = createBuffer()
        frames.resize(1920, 1080)

        val copiedRegions = mutableListOf<FakeRect>()
        val copier: (Long, FakeFrame, Int, Int, Int, Int) -> Boolean = { _, _, x, y, w, h ->
            copiedRegions += FakeRect(x, y, x + w, y + h)
            true
        }

        // First frame has no prior stale rect -> copies only the new region.
        assertTrue(frames.updateBackBuffer(1L, 10, 20, 100, 80, commitTimeMs = 500L, copyRegion = copier))
        assertEquals(listOf(FakeRect(10, 20, 110, 100)), copiedRegions)
        assertEquals(1L, frames.peekFrontSeq())
        assertEquals(500L, frames.peekFrontCommitMs())

        // Second frame does not cover the first stale rect -> copies both new and stale regions.
        copiedRegions.clear()
        assertTrue(frames.updateBackBuffer(1L, 200, 300, 50, 40, commitTimeMs = 516L, copyRegion = copier))
        assertEquals(
            listOf(FakeRect(200, 300, 250, 340), FakeRect(10, 20, 110, 100)),
            copiedRegions,
        )
        assertEquals(2L, frames.peekFrontSeq())
        assertEquals(516L, frames.peekFrontCommitMs())

        // Third frame covers the previous stale rect completely -> skips redundant stale copy.
        copiedRegions.clear()
        assertTrue(frames.updateBackBuffer(1L, 0, 0, 1920, 1080, commitTimeMs = 532L, copyRegion = copier))
        assertEquals(listOf(FakeRect(0, 0, 1920, 1080)), copiedRegions)
        assertEquals(3L, frames.peekFrontSeq())
        assertEquals(532L, frames.peekFrontCommitMs())

        // Failed copy must not commit or advance frontSeq.
        assertFalse(
            frames.updateBackBuffer(1L, 0, 0, 10, 10, commitTimeMs = 548L) { _, _, _, _, _, _ -> false },
        )
        assertEquals(3L, frames.peekFrontSeq())
        assertEquals(532L, frames.peekFrontCommitMs())
    }

    @Test
    fun commitFrameStoresPrimitiveStaleBoundsWithoutAllocatingRectObjects() {
        val rectAllocations = AtomicInteger(0)
        var nextId = 0
        val frames = DoubleBufferedFrames(
            allocate = { w, h -> FakeFrame(w, h, "alloc-${++nextId}") },
            scaleCopy = { src, w, h -> FakeFrame(w, h, "copy-of-${src.tag}") },
            widthOf = FakeFrame::width,
            heightOf = FakeFrame::height,
            makeRect = { l, t, r, b ->
                rectAllocations.incrementAndGet()
                FakeRect(l, t, r, b)
            },
            nowMillis = { 100L },
        )
        frames.resize(1920, 1080)

        repeat(5) { step ->
            frames.commitFrame(step, step * 2, 100, 50, commitTimeMs = 200L + step)
        }
        assertEquals(0, rectAllocations.get())

        val stale = frames.staleRect()
        assertEquals(FakeRect(4, 8, 104, 58), stale)
        assertEquals(1, rectAllocations.get())
    }

    @Test
    fun peekFrontDoesNotBlockWhileSnapshotHoldsBufferLock() {
        val enteredScaleCopy = CountDownLatch(1)
        val allowScaleFinish = CountDownLatch(1)
        val snapshotResult = AtomicReference<FakeFrame?>()
        var nextId = 0
        val frames = DoubleBufferedFrames(
            allocate = { w, h -> FakeFrame(w, h, "alloc-${++nextId}") },
            scaleCopy = { src, w, h ->
                enteredScaleCopy.countDown()
                allowScaleFinish.await(5, TimeUnit.SECONDS)
                FakeFrame(w, h, "copy-of-${src.tag}")
            },
            widthOf = FakeFrame::width,
            heightOf = FakeFrame::height,
            makeRect = ::FakeRect,
            nowMillis = { 100L },
        )
        frames.resize(1920, 1080)
        frames.commitFrame(0, 0, 1920, 1080)
        val expectedFront = frames.peekFront()

        val snapshotThread = Thread {
            snapshotResult.set(frames.snapshot(960))
        }
        snapshotThread.start()
        try {
            assertTrue(enteredScaleCopy.await(5, TimeUnit.SECONDS))
            // Must succeed immediately without waiting for snapshotThread to release lock.
            assertSame(expectedFront, frames.peekFront())
        } finally {
            allowScaleFinish.countDown()
            snapshotThread.join(5_000L)
        }
        assertNotNull(snapshotResult.get())
    }
}
