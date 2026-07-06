package com.hanfengruyue.pocketrdp.core.rdp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.junit.Assert.assertFalse
import org.junit.Test

class RdpClientArchitectureTest {
    @Test
    fun rdpClientIsNotProcessSingleton() {
        val source = Files.readString(
            projectRoot().resolve(
                "core-rdp/src/main/kotlin/com/hanfengruyue/pocketrdp/core/rdp/RdpClient.kt",
            ),
        )

        assertFalse(
            "Each active SessionViewModel needs its own RdpClient/buffer/native instance; " +
                "a process singleton makes concurrent sessions reuse the same framebuffer.",
            Regex("""(?m)^\s*@Singleton\s*$""").containsMatchIn(source),
        )
        assertFalse(
            "RdpClient must not import the Singleton scope; keep it session/ViewModel-owned.",
            source.contains("import javax.inject.Singleton"),
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
