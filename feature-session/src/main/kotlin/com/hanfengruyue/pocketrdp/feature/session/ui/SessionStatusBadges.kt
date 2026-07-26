package com.hanfengruyue.pocketrdp.feature.session.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hanfengruyue.pocketrdp.core.rdp.BitmapBuffer
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransport
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportStats
import com.hanfengruyue.pocketrdp.feature.session.R
import com.hanfengruyue.pocketrdp.feature.session.SessionConnectionStatus
import com.hanfengruyue.pocketrdp.feature.session.input.UserTransform
import kotlin.math.roundToInt

/**
 * Compact session-status indicator for the TopAppBar title slot.
 *
 * Renders as a single row:  [●] 8.163.3.48:41120 ⏷
 * Tapping anywhere on the row opens a dropdown with the session metrics
 * (resolution, frame rate, control latency, transport, sticky modifiers, last error).
 *
 * Latency breakdown (2026 操控延迟 audit): instead of one merged scalar, the dropdown now shows the
 * pipeline split so "is it network / server / decode / display?" is answerable in the field —
 * **操控延迟**（输入→解码), **显示延迟**（解码→上屏, the part the felt-latency metric structurally can't
 * measure), **网络往返** (raw RTT, no longer folded into the control number — the clamp was dropped),
 * and a **采样** row (accepted vs discarded discrete-input samples: a high discard ratio means presses
 * aren't producing frames, i.e. the cost is server-side/inert-screen, not the client).
 */
@Suppress("LongParameterList") // Compose title slot receives independently changing UI state values.
@Composable
fun SessionStatusTitle(
    status: SessionConnectionStatus,
    connectionName: String,
    connectionId: Long,
    remoteWidth: Int,
    remoteHeight: Int,
    fps: Int,
    controlLatencyMs: Int,
    presentLagMs: Int,
    networkRttMs: Int,
    latencyAccepted: Int,
    latencyDiscarded: Int,
    transport: RdpTransport,
    transportStats: RdpTransportStats,
    host: String?,
    stickyModifierLabels: List<String>,
    lastError: String?,
    menuContainerColor: Color = Color.Black.copy(alpha = 0.7f),
    menuContentColor: Color = Color.White,
    glassBuffer: BitmapBuffer? = null,
    glassViewportSize: IntSize = IntSize.Zero,
    glassViewportPositionOnScreen: Offset = Offset.Zero,
    glassTransformState: State<UserTransform>? = null,
    onErrorClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var statusTitlePositionOnScreen by remember { mutableStateOf(Offset.Zero) }
    var menuPositionOnScreen by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val popupOffset = remember(density) {
        with(density) { IntOffset(0, STATUS_MENU_VERTICAL_OFFSET.roundToPx()) }
    }
    val menuMaxHeight = (
        LocalConfiguration.current.screenHeightDp.dp -
            with(density) { statusTitlePositionOnScreen.y.toDp() } -
            STATUS_MENU_VERTICAL_OFFSET -
            STATUS_MENU_SCREEN_EDGE_PADDING
    ).coerceAtLeast(1.dp)
    val titleText = connectionName.ifBlank { stringResource(R.string.session_status_title_fallback, connectionId) }
    val totalLatency = totalLatencyLabel(controlLatencyMs, presentLagMs, networkRttMs)
    Box(
        modifier = Modifier.onGloballyPositioned {
            statusTitlePositionOnScreen = it.positionOnScreen()
        },
    ) {
        Row(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { expanded = true })
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(status)
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.session_cd_expand_status),
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = popupOffset,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = STATUS_MENU_MIN_WIDTH, max = STATUS_MENU_MAX_WIDTH)
                        .onGloballyPositioned { menuPositionOnScreen = it.positionOnScreen() }
                        .clip(STATUS_MENU_SHAPE),
                ) {
                    StatusMenuGlassBackground(
                        buffer = glassBuffer,
                        viewportSize = glassViewportSize,
                        viewportPositionOnScreen = glassViewportPositionOnScreen,
                        transformState = glassTransformState,
                        menuPositionOnScreen = menuPositionOnScreen,
                        containerColor = menuContainerColor,
                        modifier = Modifier.matchParentSize(),
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = menuMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        StatusMenuItem(
                            leadingIcon = { StatusDot(status) },
                            onClick = { expanded = false },
                        ) {
                            Text(
                                statusFullLabel(status),
                                style = MaterialTheme.typography.labelLarge,
                                color = menuContentColor,
                            )
                        }
                        if (!host.isNullOrBlank()) {
                            StatusMenuItem(
                                leadingIcon = { MenuRowIcon(Icons.Default.Dns, menuContentColor) },
                                onClick = { expanded = false },
                            ) {
                                MenuText(stringResource(R.string.session_status_host, host), menuContentColor)
                            }
                        }
                        if (remoteWidth > 0 && remoteHeight > 0) {
                            StatusMenuItem(
                                leadingIcon = { MenuRowIcon(Icons.Default.AspectRatio, menuContentColor) },
                                onClick = { expanded = false },
                            ) {
                                MenuText(
                                    stringResource(R.string.session_status_resolution, remoteWidth, remoteHeight),
                                    menuContentColor,
                                )
                            }
                        }
                        if (status is SessionConnectionStatus.Connected) {
                            StatusMenuItem(
                                leadingIcon = { MenuRowIcon(Icons.Default.Speed, menuContentColor) },
                                onClick = { expanded = false },
                            ) {
                                MenuText(stringResource(R.string.session_status_fps, fps), menuContentColor)
                            }
                            StatusMenuItem(
                                leadingIcon = { MenuRowIcon(Icons.Default.SettingsEthernet, menuContentColor) },
                                onClick = { expanded = false },
                            ) {
                                MenuText(
                                    stringResource(R.string.session_status_transport, transportLabel(transport)),
                                    menuContentColor,
                                )
                            }
                            StatusMenuItem(
                                leadingIcon = { MenuRowIcon(Icons.Default.Bolt, menuContentColor) },
                                onClick = { expanded = false },
                            ) {
                                MenuText(
                                    stringResource(R.string.session_status_total_latency, totalLatency),
                                    menuContentColor,
                                )
                            }
                            StatusMetricRow(
                                icon = Icons.Default.Bolt,
                                text = stringResource(
                                    R.string.session_status_control_latency,
                                    totalLatencyLabel(controlLatencyMs),
                                ),
                                contentColor = menuContentColor,
                                onClick = { expanded = false },
                            )
                            StatusMetricRow(
                                icon = Icons.Default.Speed,
                                text = stringResource(
                                    R.string.session_status_display_latency,
                                    totalLatencyLabel(presentLagMs),
                                ),
                                contentColor = menuContentColor,
                                onClick = { expanded = false },
                            )
                            StatusMetricRow(
                                icon = Icons.Default.SettingsEthernet,
                                text = stringResource(
                                    R.string.session_status_network_rtt,
                                    totalLatencyLabel(networkRttMs),
                                ),
                                contentColor = menuContentColor,
                                onClick = { expanded = false },
                            )
                            if (latencyAccepted > 0 || latencyDiscarded > 0) {
                                StatusMetricRow(
                                    icon = Icons.Default.Speed,
                                    text = stringResource(
                                        R.string.session_status_samples,
                                        latencyAccepted,
                                        latencyDiscarded,
                                    ),
                                    contentColor = menuContentColor,
                                    onClick = { expanded = false },
                                )
                            }
                            if (transportStats != RdpTransportStats()) {
                                StatusMetricRow(
                                    icon = Icons.Default.SettingsEthernet,
                                    text = stringResource(
                                        R.string.session_status_transport_stats,
                                        transportStats.inBytes,
                                        transportStats.outBytes,
                                        transportStats.retransmits,
                                    ),
                                    contentColor = menuContentColor,
                                    onClick = { expanded = false },
                                )
                            }
                            if (transportStats.hasFailureDetails()) {
                                StatusMetricRow(
                                    icon = Icons.Default.ErrorOutline,
                                    text = stringResource(
                                        R.string.session_status_transport_error,
                                        transportStats.failureStage,
                                        java.lang.Long.toHexString(transportStats.tunnelHr),
                                        transportStats.socketError,
                                    ),
                                    contentColor = menuContentColor,
                                    onClick = { expanded = false },
                                )
                            }
                        }
                        if (stickyModifierLabels.isNotEmpty()) {
                            StatusMenuItem(
                                leadingIcon = { MenuRowIcon(Icons.Default.Keyboard, menuContentColor) },
                                onClick = { expanded = false },
                            ) {
                                MenuText(
                                    stringResource(
                                        R.string.session_status_sticky_modifiers,
                                        stickyModifierLabels.joinToString("+"),
                                    ),
                                    menuContentColor,
                                )
                            }
                        }
                        if (!lastError.isNullOrBlank()) {
                            StatusMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onErrorClick()
                                },
                            ) {
                                Text(
                                    stringResource(R.string.session_status_last_error, lastError),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFCDD2),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMetricRow(
    icon: ImageVector,
    text: String,
    contentColor: Color,
    onClick: () -> Unit,
) {
    StatusMenuItem(
        leadingIcon = { MenuRowIcon(icon, contentColor) },
        onClick = onClick,
    ) {
        MenuText(text, contentColor)
    }
}

private fun RdpTransportStats.hasFailureDetails(): Boolean =
    failureStage != 0L || tunnelHr != 0L || socketError != 0L

@Composable
private fun StatusMenuGlassBackground(
    buffer: BitmapBuffer?,
    viewportSize: IntSize,
    viewportPositionOnScreen: Offset,
    transformState: State<UserTransform>?,
    menuPositionOnScreen: Offset,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    val frame = remember(buffer, viewportSize) {
        buffer?.snapshot(maxOf(viewportSize.width, viewportSize.height).coerceAtLeast(1))
    }
    val frameImage = remember(frame) { frame?.asImageBitmap() }
    Box(modifier = modifier) {
        if (
            containerColor.alpha > 0f &&
            frame != null &&
            frameImage != null &&
            viewportSize.width > 0 &&
            viewportSize.height > 0
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .blur(STATUS_MENU_BACKDROP_BLUR_RADIUS),
            ) {
                val scale = minOf(
                    viewportSize.width.toFloat() / frame.width.toFloat(),
                    viewportSize.height.toFloat() / frame.height.toFloat(),
                )
                val baseWidth = frame.width * scale
                val baseHeight = frame.height * scale
                val baseLeft = (viewportSize.width - baseWidth) / 2f
                val baseTop = (viewportSize.height - baseHeight) / 2f
                val transform = transformState?.value ?: UserTransform()
                val centerX = viewportSize.width / 2f
                val centerY = viewportSize.height / 2f
                val transformedLeft = centerX + (baseLeft - centerX) * transform.zoom + transform.panX
                val transformedTop = centerY + (baseTop - centerY) * transform.zoom +
                    transform.panY + transform.offsetY
                val transformedWidth = (baseWidth * transform.zoom).roundToInt().coerceAtLeast(1)
                val transformedHeight = (baseHeight * transform.zoom).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = frameImage,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset(
                        transformedLeft.roundToInt() -
                            (menuPositionOnScreen.x - viewportPositionOnScreen.x).roundToInt(),
                        transformedTop.roundToInt() -
                            (menuPositionOnScreen.y - viewportPositionOnScreen.y).roundToInt(),
                    ),
                    dstSize = IntSize(transformedWidth, transformedHeight),
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .statusMenuGlassTint(containerColor),
        )
    }
}

private fun Modifier.statusMenuGlassTint(containerColor: Color): Modifier =
    this
        .background(
            containerColor.copy(
                alpha = (containerColor.alpha * STATUS_MENU_TINT_MULTIPLIER).coerceIn(0f, 0.72f),
            ),
        )
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = STATUS_MENU_HIGHLIGHT_ALPHA * containerColor.alpha),
                    Color.White.copy(alpha = STATUS_MENU_HIGHLIGHT_ALPHA * 0.38f * containerColor.alpha),
                    Color.Black.copy(alpha = STATUS_MENU_SHADOW_ALPHA * containerColor.alpha),
                ),
            ),
        )
        .drawBehind {
            val stroke = 1.dp.toPx()
            val radius = STATUS_MENU_CORNER_RADIUS.toPx()
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
        }

@Composable
private fun StatusMenuItem(
    leadingIcon: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = STATUS_MENU_ITEM_MIN_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(30.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            leadingIcon?.invoke()
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

/** Small muted leading icon for a status dropdown row. */
@Composable
private fun MenuRowIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

@Composable
private fun MenuText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun totalLatencyLabel(vararg values: Int): String {
    val valid = values.filter { it >= 0 }
    return if (valid.isEmpty()) stringResource(R.string.session_status_measuring) else "${valid.sum()} ms"
}

@Composable
private fun transportLabel(transport: RdpTransport): String = when (transport) {
    RdpTransport.TCP,
    RdpTransport.TCP_FALLBACK -> "TCP"
    RdpTransport.UDP_R,
    RdpTransport.UDP_L,
    RdpTransport.UDP2 -> "UDP"
    RdpTransport.UNKNOWN -> stringResource(R.string.session_status_measuring)
}

@Composable
private fun StatusDot(status: SessionConnectionStatus) {
    val color = when (status) {
        SessionConnectionStatus.Idle -> Color(0xFF9E9E9E)
        SessionConnectionStatus.Connecting -> Color(0xFFFFB300)
        SessionConnectionStatus.Connected -> Color(0xFF43A047)
        is SessionConnectionStatus.Disconnected -> Color(0xFF9E9E9E)
        is SessionConnectionStatus.Failed -> Color(0xFFE53935)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun statusFullLabel(status: SessionConnectionStatus): String = when (status) {
    SessionConnectionStatus.Idle -> stringResource(R.string.session_status_idle)
    SessionConnectionStatus.Connecting -> stringResource(R.string.session_status_connecting)
    SessionConnectionStatus.Connected -> stringResource(R.string.session_status_connected)
    is SessionConnectionStatus.Disconnected -> stringResource(
        R.string.session_status_disconnected,
        status.reason ?: stringResource(R.string.session_status_unknown_reason),
    )
    is SessionConnectionStatus.Failed -> stringResource(R.string.session_status_failed)
}

private val STATUS_MENU_SHAPE = RoundedCornerShape(18.dp)
private val STATUS_MENU_CORNER_RADIUS = 18.dp
private val STATUS_MENU_BACKDROP_BLUR_RADIUS = 10.dp
private val STATUS_MENU_VERTICAL_OFFSET = 36.dp
private val STATUS_MENU_SCREEN_EDGE_PADDING = 8.dp
private val STATUS_MENU_MIN_WIDTH = 220.dp
private val STATUS_MENU_MAX_WIDTH = 320.dp
private val STATUS_MENU_ITEM_MIN_HEIGHT = 44.dp
private const val STATUS_MENU_TINT_MULTIPLIER = 0.7f
private const val STATUS_MENU_EDGE_ALPHA = 0.28f
private const val STATUS_MENU_HIGHLIGHT_ALPHA = 0.14f
private const val STATUS_MENU_SHADOW_ALPHA = 0.18f
