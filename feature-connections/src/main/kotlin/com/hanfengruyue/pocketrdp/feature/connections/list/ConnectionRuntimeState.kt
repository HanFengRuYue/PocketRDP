package com.hanfengruyue.pocketrdp.feature.connections.list

import android.graphics.Bitmap

data class ConnectionRuntimeState(
    val activeSessionIds: Set<Long> = emptySet(),
    val liveThumbnails: Map<Long, Bitmap> = emptyMap(),
)
