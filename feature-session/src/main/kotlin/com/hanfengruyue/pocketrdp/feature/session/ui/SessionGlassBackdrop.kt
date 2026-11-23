package com.hanfengruyue.pocketrdp.feature.session.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hanfengruyue.pocketrdp.feature.session.input.UserTransform
import kotlin.math.roundToInt

@Stable
internal data class GlassBackdropFrame(
    val width: Int,
    val height: Int,
    val image: ImageBitmap,
)

@Stable
internal class FloatingGlassContext(
    val backdropState: State<GlassBackdropFrame?>,
    val viewportSizeState: State<IntSize>,
    val viewportPositionOnScreenState: State<Offset>,
    val transformState: State<UserTransform>,
)

@Stable
internal class ToolbarGlassChrome(
    val backdropState: State<GlassBackdropFrame?>,
    val viewportSizeState: State<IntSize>,
    val viewportTopInRootState: State<Float>,
    val transformState: State<UserTransform>,
    val containerColor: Color,
)

internal enum class GlassChromeEdge { Top, Bottom }

internal fun DrawScope.drawProjectedGlassBackdrop(
    frame: GlassBackdropFrame,
    viewportSize: IntSize,
    transform: UserTransform,
    surfaceOffsetX: Float,
    surfaceOffsetY: Float,
) {
    val hasViewport = viewportSize.width > 0 && viewportSize.height > 0
    val hasFrame = frame.width > 0 && frame.height > 0
    if (!hasViewport || !hasFrame) return
    val scale = minOf(
        viewportSize.width.toFloat() / frame.width.toFloat(),
        viewportSize.height.toFloat() / frame.height.toFloat(),
    )
    val baseWidth = frame.width * scale
    val baseHeight = frame.height * scale
    val baseLeft = (viewportSize.width - baseWidth) / 2f
    val baseTop = (viewportSize.height - baseHeight) / 2f
    val centerX = viewportSize.width / 2f
    val centerY = viewportSize.height / 2f
    val transformedLeft = centerX + (baseLeft - centerX) * transform.zoom + transform.panX
    val transformedTop = centerY + (baseTop - centerY) * transform.zoom +
        transform.panY + transform.offsetY
    val transformedWidth = (baseWidth * transform.zoom).roundToInt().coerceAtLeast(1)
    val transformedHeight = (baseHeight * transform.zoom).roundToInt().coerceAtLeast(1)
    drawImage(
        image = frame.image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(frame.width, frame.height),
        dstOffset = IntOffset(
            transformedLeft.roundToInt() - surfaceOffsetX.roundToInt(),
            transformedTop.roundToInt() - surfaceOffsetY.roundToInt(),
        ),
        dstSize = IntSize(transformedWidth, transformedHeight),
    )
}

@Composable
internal fun GlassChromeBackground(
    backdropState: State<GlassBackdropFrame?>,
    viewportSizeState: State<IntSize>,
    transformState: State<UserTransform>,
    chromeTopPx: () -> Float,
    containerColor: Color,
    edge: GlassChromeEdge,
    modifier: Modifier = Modifier,
) {
    val blurRadius = glassBlurRadiusForAlpha(containerColor.alpha)
    val tintColor = remember(containerColor) {
        containerColor.copy(alpha = (containerColor.alpha * GLASS_TINT_MULTIPLIER).coerceIn(0f, GLASS_MAX_TINT_ALPHA))
    }
    val tintBrush = remember(containerColor, edge) {
        Brush.verticalGradient(
            colors = when (edge) {
                GlassChromeEdge.Top -> listOf(
                    Color.Black.copy(alpha = GLASS_SHADOW_ALPHA * containerColor.alpha),
                    Color.White.copy(alpha = GLASS_HIGHLIGHT_ALPHA * GLASS_MID_TOP_HIGHLIGHT * containerColor.alpha),
                    Color.White.copy(alpha = GLASS_HIGHLIGHT_ALPHA * containerColor.alpha),
                )
                GlassChromeEdge.Bottom -> listOf(
                    Color.White.copy(alpha = GLASS_HIGHLIGHT_ALPHA * containerColor.alpha),
                    Color.White.copy(alpha = GLASS_HIGHLIGHT_ALPHA * GLASS_MID_BOTTOM_HIGHLIGHT * containerColor.alpha),
                    Color.Black.copy(alpha = GLASS_SHADOW_ALPHA * containerColor.alpha),
                )
            },
        )
    }
    Box(modifier = modifier) {
        if (containerColor.alpha > 0f) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .blur(blurRadius),
            ) {
                val frame = backdropState.value ?: return@Canvas
                drawProjectedGlassBackdrop(
                    frame = frame,
                    viewportSize = viewportSizeState.value,
                    transform = transformState.value,
                    surfaceOffsetX = 0f,
                    surfaceOffsetY = chromeTopPx(),
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(tintColor)
                .background(tintBrush)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    val edgeY = if (edge == GlassChromeEdge.Top) stroke / 2f else size.height - stroke / 2f
                    val shadowY = if (edge == GlassChromeEdge.Top) {
                        stroke * GLASS_SHADOW_STROKE_MULT
                    } else {
                        size.height - stroke * GLASS_SHADOW_STROKE_MULT
                    }
                    drawLine(
                        color = Color.Black.copy(alpha = GLASS_SHADOW_ALPHA * containerColor.alpha),
                        start = Offset(0f, shadowY),
                        end = Offset(size.width, shadowY),
                        strokeWidth = stroke * 2f,
                    )
                    drawLine(
                        color = Color.White.copy(alpha = GLASS_EDGE_ALPHA * containerColor.alpha),
                        start = Offset(0f, edgeY),
                        end = Offset(size.width, edgeY),
                        strokeWidth = stroke,
                    )
                },
        )
    }
}

@Composable
internal fun FloatingGlassBackground(
    glass: FloatingGlassContext,
    controlPositionOnScreen: () -> Offset?,
    containerColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val blurRadius = glassBlurRadiusForAlpha(containerColor.alpha)
    val tintColor = remember(containerColor) {
        containerColor.copy(alpha = (containerColor.alpha * GLASS_TINT_MULTIPLIER).coerceIn(0f, GLASS_MAX_TINT_ALPHA))
    }
    val tintBrush = remember(containerColor) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = GLASS_HIGHLIGHT_ALPHA * containerColor.alpha),
                Color.White.copy(alpha = GLASS_HIGHLIGHT_ALPHA * GLASS_MID_FLOATING_HIGHLIGHT * containerColor.alpha),
                Color.Black.copy(alpha = GLASS_SHADOW_ALPHA * containerColor.alpha),
            ),
        )
    }
    Box(modifier = modifier.clip(shape)) {
        if (containerColor.alpha > 0f) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .blur(blurRadius),
            ) {
                val frame = glass.backdropState.value ?: return@Canvas
                val controlPos = controlPositionOnScreen() ?: return@Canvas
                val viewportPos = glass.viewportPositionOnScreenState.value
                drawProjectedGlassBackdrop(
                    frame = frame,
                    viewportSize = glass.viewportSizeState.value,
                    transform = glass.transformState.value,
                    surfaceOffsetX = controlPos.x - viewportPos.x,
                    surfaceOffsetY = controlPos.y - viewportPos.y,
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(color = tintColor, shape = shape)
                .background(brush = tintBrush, shape = shape)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = GLASS_EDGE_ALPHA * containerColor.alpha),
                    shape = shape,
                ),
        )
    }
}

@Composable
internal fun StatusMenuGlassBackground(
    glass: FloatingGlassContext?,
    menuPositionOnScreen: () -> Offset,
    containerColor: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val tintColor = remember(containerColor) {
        containerColor.copy(
            alpha = (containerColor.alpha * STATUS_MENU_TINT_MULTIPLIER).coerceIn(0f, STATUS_MENU_MAX_TINT_ALPHA),
        )
    }
    val tintBrush = remember(containerColor) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = STATUS_MENU_HIGHLIGHT_ALPHA * containerColor.alpha),
                Color.White.copy(
                    alpha = STATUS_MENU_HIGHLIGHT_ALPHA * STATUS_MENU_MID_HIGHLIGHT * containerColor.alpha,
                ),
                Color.Black.copy(alpha = STATUS_MENU_SHADOW_ALPHA * containerColor.alpha),
            ),
        )
    }
    Box(modifier = modifier) {
        if (containerColor.alpha > 0f && glass != null) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .blur(GLASS_BACKDROP_MAX_BLUR_RADIUS),
            ) {
                val frame = glass.backdropState.value ?: return@Canvas
                val menuPos = menuPositionOnScreen()
                val viewportPos = glass.viewportPositionOnScreenState.value
                drawProjectedGlassBackdrop(
                    frame = frame,
                    viewportSize = glass.viewportSizeState.value,
                    transform = glass.transformState.value,
                    surfaceOffsetX = menuPos.x - viewportPos.x,
                    surfaceOffsetY = menuPos.y - viewportPos.y,
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(tintColor)
                .background(tintBrush)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    val radius = cornerRadius.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = STATUS_MENU_EDGE_ALPHA * containerColor.alpha),
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(
                            width = (size.width - stroke).coerceAtLeast(0f),
                            height = (size.height - stroke).coerceAtLeast(0f),
                        ),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(stroke),
                    )
                },
        )
    }
}

private fun glassBlurRadiusForAlpha(alpha: Float): Dp {
    val progress = alpha.coerceIn(0f, 1f)
    return GLASS_BACKDROP_MIN_BLUR_RADIUS +
        (GLASS_BACKDROP_MAX_BLUR_RADIUS - GLASS_BACKDROP_MIN_BLUR_RADIUS) * progress
}

private const val GLASS_EDGE_ALPHA = 0.22f
private const val GLASS_HIGHLIGHT_ALPHA = 0.14f
private const val GLASS_SHADOW_ALPHA = 0.16f
private const val GLASS_TINT_MULTIPLIER = 0.58f
private const val GLASS_MAX_TINT_ALPHA = 0.62f
private const val GLASS_MID_TOP_HIGHLIGHT = 0.55f
private const val GLASS_MID_BOTTOM_HIGHLIGHT = 0.45f
private const val GLASS_MID_FLOATING_HIGHLIGHT = 0.42f
private const val GLASS_SHADOW_STROKE_MULT = 1.5f
private const val STATUS_MENU_TINT_MULTIPLIER = 0.7f
private const val STATUS_MENU_MAX_TINT_ALPHA = 0.72f
private const val STATUS_MENU_EDGE_ALPHA = 0.28f
private const val STATUS_MENU_HIGHLIGHT_ALPHA = 0.14f
private const val STATUS_MENU_MID_HIGHLIGHT = 0.38f
private const val STATUS_MENU_SHADOW_ALPHA = 0.18f
private val GLASS_BACKDROP_MIN_BLUR_RADIUS = 0.dp
private val GLASS_BACKDROP_MAX_BLUR_RADIUS = 10.dp
