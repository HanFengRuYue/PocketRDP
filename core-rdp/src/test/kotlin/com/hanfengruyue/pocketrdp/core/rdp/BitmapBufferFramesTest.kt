package com.hanfengruyue.pocketrdp.core.rdp

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
}
