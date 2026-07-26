package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpCommandLineTest {
    @Test
    fun richVisualsAreExplicitWhenLowLatencyVisualsAreOff() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null).copy(performanceFlags = 0),
            h264Supported = true,
        ).toList()

        val enabledVisuals = listOf(
            "/fonts",
            "/wallpaper",
            "/window-drag",
            "/menu-anims",
            "/themes",
            "/aero",
        )
        val disabledVisuals = enabledVisuals.map { "-${it.removePrefix("/")}" }
        assertTrue(args.containsAll(enabledVisuals))
        assertFalse(args.any { it in disabledVisuals })
    }

    @Test
    fun lowLatencyVisualsExplicitlyDisableRemoteEffectsButKeepFontSmoothing() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null).copy(
                performanceFlags = RdpClient.PERF_LOW_LATENCY_VISUALS,
            ),
            h264Supported = true,
        ).toList()

        val disabledVisuals = listOf(
            "-wallpaper",
            "-window-drag",
            "-menu-anims",
            "-themes",
            "-aero",
        )
        val enabledEffects = disabledVisuals.map { "/${it.removePrefix("-")}" }
        assertTrue(args.contains("/fonts"))
        assertTrue(args.containsAll(disabledVisuals))
        assertFalse(args.any { it in enabledEffects })
        assertFalse(args.contains("-fonts"))
    }

    @Test
    fun qualityFirstCodecCanBeCombinedWithRichVisuals() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null).copy(
                preferAvc420 = false,
                performanceFlags = 0,
            ),
            h264Supported = true,
        ).toList()

        assertTrue(args.contains("/gfx:AVC444"))
        assertTrue(args.contains("/menu-anims"))
        assertTrue(args.contains("/aero"))
    }

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

    @Test
    fun multitransportUsesFreeRdpBooleanSigils() {
        val disabled = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null),
            h264Supported = true,
        ).toList()
        val enabled = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null).copy(useMultitransport = true),
            h264Supported = true,
        ).toList()

        assertTrue(disabled.contains("-multitransport"))
        assertFalse(disabled.any { it.startsWith("/multitransport:") })
        assertTrue(enabled.contains("/multitransport"))
        assertFalse(enabled.contains("-multitransport"))
    }

    @Test
    fun unsafeInitialFramebufferAndColorDepthFallBackBeforeNativeAllocation() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null).copy(
                initialWidth = Int.MAX_VALUE,
                initialHeight = Int.MAX_VALUE,
                colorDepth = Int.MAX_VALUE,
            ),
            h264Supported = true,
        ).toList()

        assertTrue(args.contains("/size:1920x1080"))
        assertTrue(args.contains("/bpp:32"))
    }

    @Test
    fun legacyStandardRdpSecurityDowngradeIsDisabled() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null),
            h264Supported = true,
        ).toList()

        assertTrue(args.contains("/sec:rdp:off"))
        assertFalse(args.contains("/sec:rdp:on"))
    }

    @Test
    fun nativeAutoReconnectRetriesAreBounded() {
        val args = buildRdpCommandLine(
            params(redirectFiles = false, drivePath = null),
            h264Supported = true,
        ).toList()

        assertTrue(args.contains("/auto-reconnect"))
        assertTrue(args.contains("/auto-reconnect-max-retries:5"))
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
