package com.hanfengruyue.pocketrdp.core.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailMemoryCacheTest {

    @Test
    fun evictsLeastRecentlyUsedEntriesWhenByteCapExceeded() {
        val cache = ThumbnailMemoryCache<String>(maxBytes = MAX_BYTES) { it.length }

        cache.putAndAdvanceVersion(1L, "aaaa")
        cache.putAndAdvanceVersion(2L, "bbbb")
        // Touch entry 1 so entry 2 becomes least recently used.
        assertEquals("aaaa", cache.get(1L))
        cache.putAndAdvanceVersion(3L, "cccc")

        assertEquals("aaaa", cache.get(1L))
        assertNull(cache.get(2L))
        assertEquals("cccc", cache.get(3L))
        assertEquals(8L, cache.sizeBytes())
    }

    @Test
    fun removeFreesTrackedBytesAndOversizedEntryIsIgnored() {
        val cache = ThumbnailMemoryCache<String>(maxBytes = MAX_BYTES) { it.length }

        cache.putAndAdvanceVersion(1L, "aaaa")
        assertTrue(cache.removeAndAdvanceVersion(1L))
        assertFalse(cache.removeAndAdvanceVersion(1L))
        assertEquals(0L, cache.sizeBytes())

        cache.putAndAdvanceVersion(2L, "0123456789")
        assertNull(cache.get(2L))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun staleDiskDecodeDoesNotOverwriteConcurrentSaveOrResurrectDelete() {
        val cache = ThumbnailMemoryCache<String>(maxBytes = MAX_BYTES) { it.length }

        // Simulate load(1) snapshotting version before decode, then save(1, "new!") completing.
        val beforeSave = cache.getOrVersion(1L)
        assertNull(beforeSave.value)
        cache.putAndAdvanceVersion(1L, "new!")
        assertEquals("new!", cache.putIfVersionMatches(1L, beforeSave.version, "old!"))
        assertEquals("new!", cache.get(1L))

        // Simulate load(2) snapshotting version before decode, then delete(2) completing.
        val beforeDelete = cache.getOrVersion(2L)
        assertNull(beforeDelete.value)
        cache.removeAndAdvanceVersion(2L)
        assertNull(cache.putIfVersionMatches(2L, beforeDelete.version, "dead"))
        assertNull(cache.get(2L))

        // Uncontested decode succeeds and populates the cache.
        val uncontested = cache.getOrVersion(3L)
        assertEquals("ok!!", cache.putIfVersionMatches(3L, uncontested.version, "ok!!"))
        assertEquals("ok!!", cache.get(3L))
    }

    private companion object {
        const val MAX_BYTES = 8
    }
}
