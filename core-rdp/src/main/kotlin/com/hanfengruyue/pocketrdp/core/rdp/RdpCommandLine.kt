package com.hanfengruyue.pocketrdp.core.rdp

internal const val RDP_DRIVE_NAME = "PocketRDP"

internal fun buildRdpCommandLine(
    p: RdpConnectionParams,
    h264Supported: Boolean,
): Array<String> {
    val args = mutableListOf(
        "freerdp",
        "/gdi:sw",
        "/v:${p.host}",
        "/port:${p.port}",
        "/u:${p.username}",
        "/cert:ignore",
        "/size:${p.initialWidth}x${p.initialHeight}",
        "/bpp:${p.colorDepth}",
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

    if (p.performanceFlags and RdpClient.PERF_LOW_LATENCY_VISUALS != 0) {
        args += "-wallpaper"
        args += "-themes"
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
