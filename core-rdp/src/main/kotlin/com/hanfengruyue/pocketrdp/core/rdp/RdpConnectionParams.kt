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
    val useGfx: Boolean,
    val dynamicResolution: Boolean,
    val redirectClipboard: Boolean,
    val redirectFiles: Boolean,
    val sharedFolderUri: String?,
    val soundMode: Int,
    val desktopScaleFactor: Int,
    val initialWidth: Int,
    val initialHeight: Int,
    val acceptedCertThumbprint: String?,
)
