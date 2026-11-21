package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpCommandLineFormatTest {
    @Test
    fun bareIpv6LiteralsAreBracketedInServerArg() {
        assertEquals("[2001:db8::1]", formatRdpHost("2001:db8::1"))
        assertEquals("[::1]", formatRdpHost("::1"))
        assertEquals("[fe80::1%wlan0]", formatRdpHost(" fe80::1%wlan0 "))

        val args = buildRdpCommandLine(
            sampleParams(host = "2001:db8::1"),
            h264Supported = true,
        ).toList()
        assertTrue(args.contains("/v:[2001:db8::1]"))
        assertFalse(args.contains("/v:2001:db8::1"))
    }

    @Test
    fun bracketedIpv6Ipv4AndHostnamesArePreserved() {
        assertEquals("[2001:db8::1]", formatRdpHost("[2001:db8::1]"))
        assertEquals("192.0.2.10", formatRdpHost("192.0.2.10"))
        assertEquals("rdp.example.com", formatRdpHost(" rdp.example.com "))

        val args = buildRdpCommandLine(
            sampleParams(host = "[2001:db8::1]"),
            h264Supported = true,
        ).toList()
        assertTrue(args.contains("/v:[2001:db8::1]"))
    }

    @Test
    fun clipboardDisabledEmitsDisableSigilInsteadOfLoadingCliprdr() {
        val disabled = buildRdpCommandLine(
            sampleParams(redirectClipboard = false),
            h264Supported = true,
        ).toList()
        val enabled = buildRdpCommandLine(
            sampleParams(redirectClipboard = true),
            h264Supported = true,
        ).toList()

        assertTrue(disabled.contains("-clipboard"))
        assertFalse(disabled.any { it.startsWith("/clipboard:") })
        assertTrue(enabled.contains("/clipboard:use-selection:primary,direction-to:all"))
        assertFalse(enabled.contains("-clipboard"))
    }

    private fun sampleParams(
        host: String = "192.0.2.10",
        redirectClipboard: Boolean = true,
    ): RdpConnectionParams = RdpConnectionParams(
        connectionId = 1L,
        host = host,
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
        redirectClipboard = redirectClipboard,
        redirectFiles = false,
        drivePath = null,
        soundMode = 0,
        desktopScaleFactor = 150,
        initialWidth = 1920,
        initialHeight = 1080,
        acceptedCertThumbprint = null,
    )
}
