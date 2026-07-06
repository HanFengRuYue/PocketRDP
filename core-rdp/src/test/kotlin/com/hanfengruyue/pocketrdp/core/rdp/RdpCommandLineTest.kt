package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpCommandLineTest {
    @Test
    fun driveRedirectionIsEmittedWhenEnabledWithFilesystemPath() {
        val args = buildRdpCommandLine(
            params(redirectFiles = true, drivePath = "/storage/emulated/0"),
            h264Supported = true,
        ).toList()

        assertTrue(args.contains("/drive:$RDP_DRIVE_NAME,/storage/emulated/0"))
    }

    @Test
    fun driveRedirectionIsOmittedWhenDisabled() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = "/storage/emulated/0"),
            h264Supported = true,
        ).toList()

        assertFalse(args.any { it.startsWith("/drive:") })
    }

    @Test
    fun driveRedirectionIsOmittedForBlankOrContentUriPath() {
        val blankArgs = buildRdpCommandLine(
            params(redirectFiles = true, drivePath = " "),
            h264Supported = true,
        ).toList()
        val contentUriArgs = buildRdpCommandLine(
            params(redirectFiles = true, drivePath = "content://tree/primary%3ADownload"),
            h264Supported = true,
        ).toList()

        assertFalse(blankArgs.any { it.startsWith("/drive:") })
        assertFalse(contentUriArgs.any { it.startsWith("/drive:") })
    }

    private fun params(
        redirectFiles: Boolean,
        drivePath: String?,
    ): RdpConnectionParams = RdpConnectionParams(
        connectionId = 1L,
        host = "192.0.2.10",
        port = 3389,
        username = "user",
        domain = "",
        password = "secret",
        colorDepth = 32,
        useH264 = true,
        preferAvc420 = true,
        useGfx = true,
        dynamicResolution = true,
        useMultitransport = false,
        redirectClipboard = true,
        redirectFiles = redirectFiles,
        sharedFolderUri = null,
        drivePath = drivePath,
        soundMode = 2,
        desktopScaleFactor = 150,
        initialWidth = 1920,
        initialHeight = 1080,
        acceptedCertThumbprint = null,
    )
}
