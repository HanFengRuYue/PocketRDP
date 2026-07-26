package com.hanfengruyue.pocketrdp.core.rdp

internal const val RDP_DRIVE_NAME = "PocketRDP"

internal fun buildRdpCommandLine(
    p: RdpConnectionParams,
    h264Supported: Boolean,
): Array<String> {
    // Validate before FreeRDP allocates its initial GDI framebuffer. The resize callback's bounds
    // check is too late for a corrupted database or another internal caller supplying huge values.
    val (initialWidth, initialHeight) = safeInitialRdpSize(p.initialWidth, p.initialHeight)
    val args = mutableListOf(
        "freerdp",
        "/gdi:sw",
        "/v:${p.host}",
        "/port:${p.port}",
        "/u:${p.username}",
        "/size:${initialWidth}x$initialHeight",
        "/bpp:${p.colorDepth.takeIf { it in SUPPORTED_COLOR_DEPTHS } ?: DEFAULT_COLOR_DEPTH}",
        // Keep NLA and TLS negotiation enabled, but never downgrade to the unauthenticated legacy
        // Standard RDP Security layer (RC4/proprietary server certificate), which bypasses the
        // TLS certificate pin/TOFU callback entirely.
        "/sec:rdp:off",
    )
    if (p.password.isNotEmpty()) args += "/p:${p.password}"
    if (p.domain.isNotEmpty()) args += "/d:${p.domain}"

    when {
        p.useH264 && h264Supported -> args += if (p.preferAvc420) "/gfx:AVC420" else "/gfx:AVC444"
        p.useGfx -> args += "/gfx:RFX"
    }

    args += "/multitouch"
    if (p.dynamicResolution) args += "/dynamic-resolution"
    args += if (p.useMultitransport) "/multitransport" else "-multitransport"
    args += "/auto-reconnect"
    // FreeRDP otherwise retries a network drop 20 times before returning control to the app's own
    // bounded exponential-backoff policy. Five native attempts preserve short transient recovery
    // without holding a dead session for minutes or multiplying authentication failures.
    args += "/auto-reconnect-max-retries:5"

    if (p.performanceFlags and RdpClient.PERF_LOW_LATENCY_VISUALS != 0) {
        args += listOf(
            "/fonts",
            "-wallpaper",
            "-window-drag",
            "-menu-anims",
            "-themes",
            "-aero",
        )
    } else {
        args += listOf(
            "/fonts",
            "/wallpaper",
            "/window-drag",
            "/menu-anims",
            "/themes",
            "/aero",
        )
    }

    val scale = p.desktopScaleFactor.coerceIn(MIN_DESKTOP_SCALE, MAX_DESKTOP_SCALE)
    args += "/scale-desktop:$scale"

    val clipDir = if (p.redirectClipboard) "all" else "off"
    args += "/clipboard:use-selection:primary,direction-to:$clipDir"

    driveRedirectionArg(p.redirectFiles, p.drivePath)?.let { args += it }

    when (p.soundMode) {
        1 -> args += "/audio-mode:redirect"
        2 -> args += "/audio-mode:server"
        else -> args += "/audio-mode:none"
    }

    return args.toTypedArray()
}

internal fun driveRedirectionArg(redirectFiles: Boolean, drivePath: String?): String? {
    if (!redirectFiles) return null
    val path = drivePath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (path.startsWith("content:", ignoreCase = true)) return null
    return "/drive:$RDP_DRIVE_NAME,$path"
}

private const val MIN_DESKTOP_SCALE = 100
private const val MAX_DESKTOP_SCALE = 300
private const val DEFAULT_INITIAL_WIDTH = 1920
private const val DEFAULT_INITIAL_HEIGHT = 1080
private const val DEFAULT_COLOR_DEPTH = 32
private val SUPPORTED_COLOR_DEPTHS = setOf(16, 24, 32)

internal fun safeInitialRdpSize(width: Int, height: Int): Pair<Int, Int> =
    if (isSafeFramebufferSize(width, height)) {
        width to height
    } else {
        DEFAULT_INITIAL_WIDTH to DEFAULT_INITIAL_HEIGHT
    }
