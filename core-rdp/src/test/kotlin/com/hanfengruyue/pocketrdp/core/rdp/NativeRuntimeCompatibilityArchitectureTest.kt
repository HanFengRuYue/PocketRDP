package com.hanfengruyue.pocketrdp.core.rdp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeCompatibilityArchitectureTest {
    @Test
    fun pthreadTlsKeysPreserveAndroidOpaqueBitPattern() {
        val source = Files.readString(
            projectRoot().resolve("third_party/FreeRDP/winpr/libwinpr/thread/tls.c"),
        )

        assertTrue(
            "TlsAlloc must preserve the complete pthread_key_t bit pattern in the DWORD index.",
            source.contains("const DWORD index = (DWORD)key;"),
        )
        assertTrue(
            "TlsAlloc must reject keys that cannot round-trip through the public DWORD API.",
            source.contains("(pthread_key_t)index != key"),
        )
        assertTrue(
            "A rejected pthread key must be deleted instead of leaking its process slot.",
            source.contains("pthread_key_delete(key);"),
        )
        assertFalse(
            "Android valid pthread keys can be negative; a sign-preserving cast assertion aborts.",
            source.contains("WINPR_ASSERTING_INT_CAST(DWORD, key)"),
        )
    }

    private fun projectRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!current.resolve("settings.gradle.kts").exists()) {
            current = current.parent ?: error("Could not locate project root from ${System.getProperty("user.dir")}")
        }
        return current
    }
}
