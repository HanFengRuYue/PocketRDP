package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap

sealed interface RdpCursor {
    data object Default : RdpCursor
    data object Hidden : RdpCursor

    data class Image(
        val bitmap: Bitmap,
        val hotX: Int,
        val hotY: Int,
    ) : RdpCursor
}
