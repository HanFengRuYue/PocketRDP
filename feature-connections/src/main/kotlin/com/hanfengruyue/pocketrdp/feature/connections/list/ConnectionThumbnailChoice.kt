package com.hanfengruyue.pocketrdp.feature.connections.list

internal fun <T> chooseConnectionThumbnail(liveThumbnail: T?, diskThumbnail: T?): T? =
    liveThumbnail ?: diskThumbnail
