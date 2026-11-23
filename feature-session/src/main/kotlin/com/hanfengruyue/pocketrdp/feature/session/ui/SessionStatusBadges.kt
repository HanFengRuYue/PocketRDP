package com.hanfengruyue.pocketrdp.feature.session.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportFailure
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportPhase
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportSnapshot
import com.hanfengruyue.pocketrdp.core.rdp.RdpUdpKind
import com.hanfengruyue.pocketrdp.core.rdp.RdpUdpTunnelSnapshot
import com.hanfengruyue.pocketrdp.feature.session.R
import com.hanfengruyue.pocketrdp.feature.session.SessionConnectionStatus
import com.hanfengruyue.pocketrdp.feature.session.SessionMetricsUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Compact session-status indicator for the TopAppBar title slot.
 *
 * Renders as a single row:  [●] 8.163.3.48:41120 ⏷
 * Tapping anywhere on the row opens a dropdown with the session metrics
 * (resolution, frame rate, control latency, transport, sticky modifiers, last error).
 *
 * [metricsFlow] is collected ONLY while the dropdown popup is expanded, so 1 Hz telemetry updates
 * never recompose the collapsed TopAppBar or SessionScreen.
 */
@Suppress("LongParameterList")
@Composable
internal fun SessionStatusTitle(
    status: SessionConnectionStatus,
    connectionName: String,
    connectionId: Long,
    remoteWidth: Int,
    remoteHeight: Int,
    metricsFlow: StateFlow<SessionMetricsUiState>,
    host: String?,
    stickyModifierLabels: List<String>,
    lastError: String?,
    menuContainerColor: Color = DEFAULT_MENU_CONTAINER_COLOR,
    menuContentColor: Color = Color.White,
    glass: FloatingGlassContext? = null,
    onExpandedChange: (Boolean) -> Unit = {},
    onReconnect: () -> Unit = {},
    onErrorClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var statusTitleTopInWindowPx by remember { mutableFloatStateOf(0f) }
    val currentOnExpandedChange = rememberUpdatedState(onExpandedChange)
    LaunchedEffect(expanded) { currentOnExpandedChange.value(expanded) }
    DisposableEffect(Unit) { onDispose { currentOnExpandedChange.value(false) } }

    val density = LocalDensity.current
    val popupOffset = remember(density) {
        with(density) { IntOffset(0, STATUS_MENU_VERTICAL_OFFSET.roundToPx()) }
    }
    val titleText = connectionName.ifBlank { stringResource(R.string.session_status_title_fallback, connectionId) }
    Box(
        modifier = Modifier.onGloballyPositioned {
            statusTitleTopInWindowPx = it.positionInWindow().y
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
            val windowHeightPx = LocalWindowInfo.current.containerSize.height
            val menuMaxHeight = with(density) {
                computeStatusMenuMaxHeightPx(
                    windowHeightPx = windowHeightPx,
                    anchorTopInWindowPx = statusTitleTopInWindowPx,
                    verticalOffsetPx = STATUS_MENU_VERTICAL_OFFSET.toPx(),
                    edgePaddingPx = STATUS_MENU_SCREEN_EDGE_PADDING.toPx(),
                ).toDp()
            }
            Popup(
                alignment = Alignment.TopStart,
                offset = popupOffset,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                SessionStatusPopupContent(
                    status = status,
                    remoteWidth = remoteWidth,
                    remoteHeight = remoteHeight,
                    metricsFlow = metricsFlow,
                    host = host,
                    stickyModifierLabels = stickyModifierLabels,
                    lastError = lastError,
                    menuMaxHeight = menuMaxHeight,
                    menuContainerColor = menuContainerColor,
                    menuContentColor = menuContentColor,
                    glass = glass,
                    onDismiss = { expanded = false },
                    onReconnect = onReconnect,
                    onErrorClick = onErrorClick,
                )
            }
        }
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun SessionStatusPopupContent(
    status: SessionConnectionStatus,
    remoteWidth: Int,
    remoteHeight: Int,
    metricsFlow: StateFlow<SessionMetricsUiState>,
    host: String?,
    stickyModifierLabels: List<String>,
    lastError: String?,
    menuMaxHeight: Dp,
    menuContainerColor: Color,
    menuContentColor: Color,
    glass: FloatingGlassContext?,
    onDismiss: () -> Unit,
    onReconnect: () -> Unit,
    onErrorClick: () -> Unit,
) {
    val menuPositionOnScreen = remember { mutableStateOf(Offset.Zero) }
    val metrics by metricsFlow.collectAsStateWithLifecycle()
    val totalLatency = latencyLabel(computeTotalLatencyMs(metrics.controlLatencyMs, metrics.presentLagMs))
    Box(
        modifier = Modifier
            .widthIn(min = STATUS_MENU_MIN_WIDTH, max = STATUS_MENU_MAX_WIDTH)
            .onGloballyPositioned { menuPositionOnScreen.value = it.positionOnScreen() }
            .clip(STATUS_MENU_SHAPE),
    ) {
        StatusMenuGlassBackground(
            glass = if (status is SessionConnectionStatus.Connected) glass else null,
            menuPositionOnScreen = { menuPositionOnScreen.value },
            containerColor = menuContainerColor,
            cornerRadius = STATUS_MENU_CORNER_RADIUS,
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
                onClick = onDismiss,
            ) {
                Text(
                    statusFullLabel(status),
                    style = MaterialTheme.typography.labelLarge,
                    color = menuContentColor,
                )
            }
            if (status is SessionConnectionStatus.Failed ||
                status is SessionConnectionStatus.Disconnected
            ) {
                StatusMenuItem(
                    leadingIcon = { MenuRowIcon(Icons.Default.Refresh, menuContentColor) },
                    onClick = {
                        onDismiss()
                        onReconnect()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.session_action_reconnect),
                        style = MaterialTheme.typography.labelLarge,
                        color = menuContentColor,
                    )
                }
            }
            if (!host.isNullOrBlank()) {
                StatusMetricRow(
                    icon = Icons.Default.Dns,
                    text = stringResource(R.string.session_status_host, host),
                    contentColor = menuContentColor,
                    onClick = onDismiss,
                )
            }
            if (remoteWidth > 0 && remoteHeight > 0) {
                StatusMetricRow(
                    icon = Icons.Default.AspectRatio,
                    text = stringResource(R.string.session_status_resolution, remoteWidth, remoteHeight),
                    contentColor = menuContentColor,
                    onClick = onDismiss,
                )
            }
            if (status is SessionConnectionStatus.Connected) {
                ConnectedStatusMetricsRows(
                    metrics = metrics,
                    totalLatency = totalLatency,
                    menuContentColor = menuContentColor,
                    onDismiss = onDismiss,
                )
            }
            if (stickyModifierLabels.isNotEmpty()) {
                StatusMetricRow(
                    icon = Icons.Default.Keyboard,
                    text = stringResource(
                        R.string.session_status_sticky_modifiers,
                        stickyModifierLabels.joinToString("+"),
                    ),
                    contentColor = menuContentColor,
                    onClick = onDismiss,
                )
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
                        onDismiss()
                        onErrorClick()
                    },
                ) {
                    Text(
                        stringResource(R.string.session_status_last_error, lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = STATUS_ERROR_TEXT_COLOR,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedStatusMetricsRows(
    metrics: SessionMetricsUiState,
    totalLatency: String,
    menuContentColor: Color,
    onDismiss: () -> Unit,
) {
    val transportSnapshot = metrics.transportSnapshot
    StatusMetricRow(
        icon = Icons.Default.Speed,
        text = stringResource(R.string.session_status_fps, metrics.fps),
        contentColor = menuContentColor,
        onClick = onDismiss,
    )
    StatusMetricRow(
        icon = Icons.Default.SettingsEthernet,
        text = stringResource(R.string.session_status_transport, transportLabel(transportSnapshot)),
        contentColor = menuContentColor,
        onClick = onDismiss,
    )
    StatusMetricRow(
        icon = Icons.Default.Bolt,
        text = stringResource(R.string.session_status_total_latency, totalLatency),
        contentColor = menuContentColor,
        onClick = onDismiss,
    )
    StatusMetricRow(
        icon = Icons.Default.Bolt,
        text = stringResource(
            R.string.session_status_control_latency,
            latencyLabel(metrics.controlLatencyMs.takeIf { it >= 0 }),
        ),
        contentColor = menuContentColor,
        onClick = onDismiss,
    )
    StatusMetricRow(
        icon = Icons.Default.Speed,
        text = stringResource(
            R.string.session_status_display_latency,
            latencyLabel(metrics.presentLagMs.takeIf { it >= 0 }),
        ),
        contentColor = menuContentColor,
        onClick = onDismiss,
    )
    StatusMetricRow(
        icon = Icons.Default.SettingsEthernet,
        text = stringResource(
            R.string.session_status_network_rtt,
            latencyLabel(metrics.latencyMs.takeIf { it >= 0 }),
        ),
        contentColor = menuContentColor,
        onClick = onDismiss,
    )
    if (metrics.latencyAccepted > 0 || metrics.latencyDiscarded > 0) {
        StatusMetricRow(
            icon = Icons.Default.Speed,
            text = stringResource(
                R.string.session_status_samples,
                metrics.latencyAccepted,
                metrics.latencyDiscarded,
            ),
            contentColor = menuContentColor,
            onClick = onDismiss,
        )
    }
    TransportDiagnosticRows(
        transportSnapshot = transportSnapshot,
        menuContentColor = menuContentColor,
        onDismiss = onDismiss,
    )
}

@Composable
private fun TransportDiagnosticRows(
    transportSnapshot: RdpTransportSnapshot,
    menuContentColor: Color,
    onDismiss: () -> Unit,
) {
    val reliable = transportSnapshot.reliable
    val lossy = transportSnapshot.lossy
    if (reliable.kind != RdpUdpKind.UNKNOWN) {
        StatusMetricRow(
            icon = Icons.Default.SettingsEthernet,
            text = tunnelDiagnostic(reliable),
            contentColor = menuContentColor,
            maxLines = Int.MAX_VALUE,
            onClick = onDismiss,
        )
    }
    if (lossy.kind != RdpUdpKind.UNKNOWN) {
        StatusMetricRow(
            icon = Icons.Default.SettingsEthernet,
            text = tunnelDiagnostic(lossy),
            contentColor = menuContentColor,
            maxLines = Int.MAX_VALUE,
            onClick = onDismiss,
        )
    }
    if (transportSnapshot.hasFailureDetails()) {
        StatusMetricRow(
            icon = Icons.Default.ErrorOutline,
            text = stringResource(
                R.string.session_status_transport_error,
                transportSnapshot.failure.name,
                java.lang.Long.toHexString(transportSnapshot.tunnelHresult),
                transportSnapshot.socketError,
            ),
            contentColor = menuContentColor,
            maxLines = Int.MAX_VALUE,
            onClick = onDismiss,
        )
    }
}

@Composable
private fun StatusMetricRow(
    icon: ImageVector,
    text: String,
    contentColor: Color,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    StatusMenuItem(
        leadingIcon = { MenuRowIcon(icon, contentColor) },
        onClick = onClick,
    ) {
        MenuText(text, contentColor, maxLines = maxLines)
    }
}

private fun RdpTransportSnapshot.hasFailureDetails(): Boolean =
    failure != RdpTransportFailure.NONE || tunnelHresult != 0L || socketError != 0L

private fun tunnelDiagnostic(tunnel: RdpUdpTunnelSnapshot): String = buildString {
    append(tunnel.kind.name)
    append(" v0x")
    append(tunnel.protocolVersion.toString(16))
    append(": ")
    append(tunnel.receivedBytes)
    append(" B↓ / ")
    append(tunnel.sentBytes)
    append(" B↑, RTT ")
    append(tunnel.smoothedRttMicros / MICROS_PER_MILLISECOND)
    append(" ms, RTX ")
    append(tunnel.retransmits)
    append(", FEC ")
    append(tunnel.fecRecovered)
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
private fun MenuText(
    text: String,
    color: Color,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun computeTotalLatencyMs(controlLatencyMs: Int, presentLagMs: Int): Int? = when {
    controlLatencyMs >= 0 && presentLagMs >= 0 -> controlLatencyMs + presentLagMs
    controlLatencyMs >= 0 -> controlLatencyMs
    presentLagMs >= 0 -> presentLagMs
    else -> null
}

internal fun computeStatusMenuMaxHeightPx(
    windowHeightPx: Int,
    anchorTopInWindowPx: Float,
    verticalOffsetPx: Float,
    edgePaddingPx: Float,
): Float {
    if (windowHeightPx <= 0) return 1f
    return (windowHeightPx - anchorTopInWindowPx - verticalOffsetPx - edgePaddingPx).coerceAtLeast(1f)
}

@Composable
private fun latencyLabel(valueMs: Int?): String =
    if (valueMs == null) stringResource(R.string.session_status_measuring) else "$valueMs ms"

@Composable
private fun transportLabel(snapshot: RdpTransportSnapshot): String {
    if (!snapshot.knownVersion) return stringResource(R.string.session_status_measuring)
    return when (snapshot.phase) {
        RdpTransportPhase.NEGOTIATING -> stringResource(R.string.session_transport_negotiating_udp)
        RdpTransportPhase.WAITING_SOFT_SYNC -> stringResource(R.string.session_transport_waiting_soft_sync)
        RdpTransportPhase.UDP_FAILED -> stringResource(R.string.session_transport_udp_interrupted)
        RdpTransportPhase.RECONNECTING_TCP -> stringResource(R.string.session_transport_reconnecting_tcp)
        RdpTransportPhase.ACTIVE,
        RdpTransportPhase.TCP,
        -> transportCombinationLabel(snapshot)
        RdpTransportPhase.UNKNOWN -> stringResource(R.string.session_status_measuring)
    }
}

internal fun transportCombinationLabel(snapshot: RdpTransportSnapshot): String = buildString {
    append("TCP")
    if (snapshot.phase != RdpTransportPhase.ACTIVE) return@buildString
    if (snapshot.activeMask and RdpTransportSnapshot.MASK_UDP2 != 0L) append(" + UDP2")
    if (snapshot.activeMask and RdpTransportSnapshot.MASK_UDP_R != 0L) append(" + UDP-R")
    if (snapshot.activeMask and RdpTransportSnapshot.MASK_UDP_L != 0L) append(" + UDP-L")
}

@Composable
private fun StatusDot(status: SessionConnectionStatus) {
    val color = when (status) {
        SessionConnectionStatus.Idle -> STATUS_DOT_IDLE
        SessionConnectionStatus.Connecting,
        SessionConnectionStatus.Reconnecting,
        -> STATUS_DOT_CONNECTING
        SessionConnectionStatus.Connected -> STATUS_DOT_CONNECTED
        is SessionConnectionStatus.Disconnected -> STATUS_DOT_IDLE
        is SessionConnectionStatus.Failed -> STATUS_DOT_FAILED
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
    SessionConnectionStatus.Reconnecting -> stringResource(R.string.session_status_reconnecting)
    SessionConnectionStatus.Connected -> stringResource(R.string.session_status_connected)
    is SessionConnectionStatus.Disconnected -> stringResource(
        R.string.session_status_disconnected,
        when (status.reason) {
            "user" -> stringResource(R.string.session_status_disconnected_user)
            null -> stringResource(R.string.session_status_unknown_reason)
            else -> status.reason
        },
    )
    is SessionConnectionStatus.Failed -> stringResource(R.string.session_status_failed)
}

private val DEFAULT_MENU_CONTAINER_COLOR = Color.Black.copy(alpha = 0.7f)
private val STATUS_DOT_IDLE = Color(0xFF9E9E9E)
private val STATUS_DOT_CONNECTING = Color(0xFFFFB300)
private val STATUS_DOT_CONNECTED = Color(0xFF43A047)
private val STATUS_DOT_FAILED = Color(0xFFE53935)
private val STATUS_ERROR_TEXT_COLOR = Color(0xFFFFCDD2)
private val STATUS_MENU_SHAPE = RoundedCornerShape(18.dp)
private val STATUS_MENU_CORNER_RADIUS = 18.dp
private val STATUS_MENU_VERTICAL_OFFSET = 36.dp
private val STATUS_MENU_SCREEN_EDGE_PADDING = 8.dp
private val STATUS_MENU_MIN_WIDTH = 220.dp
private val STATUS_MENU_MAX_WIDTH = 320.dp
private val STATUS_MENU_ITEM_MIN_HEIGHT = 44.dp
private const val MICROS_PER_MILLISECOND = 1000L
