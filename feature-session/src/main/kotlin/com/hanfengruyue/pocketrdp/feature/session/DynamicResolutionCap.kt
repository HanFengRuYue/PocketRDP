package com.hanfengruyue.pocketrdp.feature.session

import kotlin.math.roundToInt

/**
 * Scale a requested dynamic-resolution size down so neither edge exceeds the connection's cap.
 * The cap is the short edge in px; the long edge is bounded to 16:9 of it.
 */
internal fun capDynamicResolutionToMax(width: Int, height: Int, capShortEdge: Int): Pair<Int, Int> {
    if (capShortEdge <= 0 || width <= 0 || height <= 0) return width to height
    val capLongEdge = capShortEdge * DYNAMIC_RES_ASPECT_LONG / DYNAMIC_RES_ASPECT_SHORT
    val shortEdge = minOf(width, height)
    val longEdge = maxOf(width, height)
    val scale = minOf(1f, capShortEdge.toFloat() / shortEdge, capLongEdge.toFloat() / longEdge)
    if (scale >= 1f) return width to height
    fun toEven(value: Float): Int = value.roundToInt().let { it - (it and 1) }.coerceAtLeast(2)
    return toEven(width * scale) to toEven(height * scale)
}

private const val DYNAMIC_RES_ASPECT_LONG = 16
private const val DYNAMIC_RES_ASPECT_SHORT = 9
