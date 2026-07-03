package com.hanfengruyue.pocketrdp.core.rdp

data class RdpConnectionParams(
    val connectionId: Long,
    val host: String,
    val port: Int,
    val username: String,
    val domain: String,
    val password: String,
    val colorDepth: Int,
    val useH264: Boolean,
    // When true (流畅优先) and H.264 is active, request /gfx:AVC420 (single YUV420 stream — half the
    // software-decode cost, no prim_YUV444 recombine) instead of /gfx:AVC444 (画质优先, full 4:4:4).
    val preferAvc420: Boolean,
    val useGfx: Boolean,
    val dynamicResolution: Boolean,
    val useMultitransport: Boolean,
    val redirectClipboard: Boolean,
    val redirectFiles: Boolean,
    val sharedFolderUri: String?,
    // POSIX directory exported to the remote via /drive (file redirection). Null/blank = no drive.
    // Must be a real native path (WinPR uses open()/stat()), NOT a SAF content:// URI, and must already
    // exist at connect time (FreeRDP silently skips a non-existent drive path).
    val drivePath: String?,
    val soundMode: Int,
    val desktopScaleFactor: Int,
    val initialWidth: Int,
    val initialHeight: Int,
    val acceptedCertThumbprint: String?,
    // Per-connection performance bitmask (mirrors ConnectionEntity.performanceFlags). Bit 1
    // (ConnectionEntity.PERF_LOW_LATENCY_VISUALS) → buildCommandLine emits -wallpaper -themes so the
    // server renders a flat desktop (低延迟视觉). 0 = unchanged. Default 0 so existing call sites stay put.
    val performanceFlags: Int = 0,
)
