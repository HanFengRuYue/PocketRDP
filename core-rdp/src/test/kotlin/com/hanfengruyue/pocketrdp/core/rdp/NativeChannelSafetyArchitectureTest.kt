package com.hanfengruyue.pocketrdp.core.rdp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChannelSafetyArchitectureTest {
    @Test
    fun monitorLayoutIsDispatchedOnlyByTheWorkerEventLoop() {
        val eventSource = source(NATIVE_ROOT.resolve("android_event.c"))
        val jniSource = source(NATIVE_ROOT.resolve("android_freerdp.c"))
        val jniBody = between(
            jniSource,
            "Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1monitor_1layout(",
            "Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1touch(",
        )

        assertTrue(eventSource.contains("case EVENT_TYPE_MONITOR_LAYOUT:"))
        assertTrue(eventSource.contains("android_disp_send_monitor_layout(afc, layout->width, layout->height)"))
        assertTrue(jniBody.contains("android_disp_is_ready(afc)"))
        assertTrue(jniBody.contains("android_event_monitor_layout_new"))
        assertTrue(jniBody.contains("android_push_event"))
        assertFalse(
            "JNI must never dereference/call the DISP channel context from the caller thread.",
            jniBody.contains("android_disp_send_monitor_layout("),
        )

        val dispSource = source(NATIVE_ROOT.resolve("android_disp.c"))
        assertTrue(dispSource.contains("InterlockedExchange(&afc->dispReady, 1)"))
        assertTrue(dispSource.contains("InterlockedExchange(&afc->dispReady, 0)"))
        assertTrue(dispSource.contains("InterlockedCompareExchange(&afc->dispReady, 0, 0)"))
    }

    @Test
    fun remoteClipboardPrefersUnicodeAndSerializesFormatRequests() {
        val cliprdrSource = source(NATIVE_ROOT.resolve("android_cliprdr.c"))
        val headerSource = source(NATIVE_ROOT.resolve("android_freerdp.h"))
        val preferredBody = between(
            cliprdrSource,
            "static UINT32 android_cliprdr_preferred_text_format(",
            "static UINT android_cliprdr_send_client_format_data_request(",
        )
        val responseBody = between(
            cliprdrSource,
            "android_cliprdr_server_format_data_response(",
            "static UINT android_cliprdr_server_file_contents_request(",
        )

        val unicodeCheck = preferredBody.indexOf("formatId == CF_UNICODETEXT")
        val ansiCheck = preferredBody.indexOf("formatId == CF_TEXT")
        assertTrue("Unicode must be checked before the ANSI fallback.", unicodeCheck >= 0 && ansiCheck > unicodeCheck)
        assertTrue(headerSource.contains("BOOL clipboardRequestPending;"))
        assertTrue(headerSource.contains("BOOL clipboardFormatListPending;"))
        assertTrue(cliprdrSource.contains("if (afc->clipboardRequestPending)"))
        assertTrue(cliprdrSource.contains("afc->clipboardFormatListPending = TRUE;"))

        assertTrue(responseBody.contains("const UINT32 formatId = afc->requestedFormatId;"))
        assertTrue(responseBody.contains("if (afc->clipboardFormatListPending)"))
        assertTrue(responseBody.contains("goto complete;"))
        assertTrue(responseBody.contains("android_cliprdr_complete_server_format_data_request(cliprdr);"))
        assertFalse(
            "A response has no format ID; decoding must use the in-flight request, not a newer list.",
            responseBody.contains("serverFormats["),
        )
    }

    private fun source(path: Path): String = Files.readString(projectRoot().resolve(path))

    private fun between(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        require(startIndex >= 0) { "Missing source marker: $start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(endIndex > startIndex) { "Missing source marker: $end" }
        return source.substring(startIndex, endIndex)
    }

    private fun projectRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!current.resolve("settings.gradle.kts").exists()) {
            current = current.parent ?: error("Could not locate project root from ${System.getProperty("user.dir")}")
        }
        return current
    }

    private companion object {
        val NATIVE_ROOT: Path = Path.of(
            "third_party/FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp",
        )
    }
}
