package com.hanfengruyue.pocketrdp.core.rdp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun nativeCallbacksHaveNoProcessWideFallbackListener() {
        val source = Files.readString(
            projectRoot().resolve(
                "core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java",
            ),
        )

        assertFalse(
            "Late callbacks from one native instance must not fall through to another session.",
            source.contains("private static EventListener listener;") ||
                source.contains("private static UIEventListener uiListener;") ||
                source.contains("setEventListener(") ||
                source.contains("setUIEventListener("),
        )
    }

    @Test
    fun winprJniIsInitializedBeforeFreeRdpBridgeLoads() {
        val source = Files.readString(
            projectRoot().resolve(
                "core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java",
            ),
        )

        val winprLoad = source.indexOf("System.loadLibrary(\"winpr3\")")
        val bridgeLoad = source.indexOf("System.loadLibrary(\"freerdp-android\")")
        assertTrue("The pinned WinPR JNI library must be explicitly loaded.", winprLoad >= 0)
        assertTrue(
            "WinPR JNI_OnLoad must run before freerdp-android maps it as a dependency.",
            winprLoad < bridgeLoad,
        )
    }

    @Test
    fun certificatePromptsDoNotRemainRegisteredAsReconnects() {
        val source = Files.readString(
            projectRoot().resolve(
                "core-rdp/src/main/kotlin/com/hanfengruyue/pocketrdp/core/rdp/RdpClient.kt",
            ),
        )
        val promptBodies = Regex(
            """CertificateTerminalDisposition\.PROMPT\s*->\s*\{(.*?)^\s*\}""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE),
        ).findAll(source).map { it.groupValues[1] }.toList()

        assertTrue("Expected every certificate terminal path to handle PROMPT", promptBodies.size >= 3)
        assertTrue(
            "A certificate prompt has no live native session and must release its registry entry.",
            promptBodies.all { body ->
                body.contains("sessionRegistry.unregister(") &&
                    !body.contains("sessionRegistry.markReconnecting(")
            },
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
