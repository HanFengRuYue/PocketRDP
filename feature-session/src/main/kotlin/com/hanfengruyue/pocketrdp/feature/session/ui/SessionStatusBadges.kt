package com.hanfengruyue.pocketrdp.feature.session.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransport
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportStats
import com.hanfengruyue.pocketrdp.feature.session.R
import com.hanfengruyue.pocketrdp.feature.session.SessionConnectionStatus

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
    onErrorClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val titleText = connectionName.ifBlank { stringResource(R.string.session_status_title_fallback, connectionId) }
    val totalLatency = totalLatencyLabel(controlLatencyMs, presentLagMs, networkRttMs)
    Box {
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = menuContainerColor,
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusFullLabel(status),
                            style = MaterialTheme.typography.labelLarge,
                            color = menuContentColor,
                        )
                    }
                },
                onClick = { expanded = false },
            )
            if (!host.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { MenuText(stringResource(R.string.session_status_host, host), menuContentColor) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Dns, menuContentColor) },
                    onClick = { expanded = false },
                )
            }
            if (remoteWidth > 0 && remoteHeight > 0) {
                DropdownMenuItem(
                    text = {
                        MenuText(
                            stringResource(R.string.session_status_resolution, remoteWidth, remoteHeight),
                            menuContentColor,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.AspectRatio, menuContentColor) },
                    onClick = { expanded = false },
                )
            }
            if (status is SessionConnectionStatus.Connected) {
                DropdownMenuItem(
                    text = { MenuText(stringResource(R.string.session_status_fps, fps), menuContentColor) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Speed, menuContentColor) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = { MenuText(stringResource(R.string.session_status_total_latency, totalLatency), menuContentColor) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Bolt, menuContentColor) },
                    onClick = { expanded = false },
                )
            }
            if (stickyModifierLabels.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        MenuText(
                            stringResource(
                                R.string.session_status_sticky_modifiers,
                                stickyModifierLabels.joinToString("+"),
                            ),
                            menuContentColor,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.Keyboard, menuContentColor) },
                    onClick = { expanded = false },
                )
            }
            if (!lastError.isNullOrBlank()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.session_status_last_error, lastError),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFCDD2),
                        )
                    },
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
                )
            }
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
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun totalLatencyLabel(vararg values: Int): String {
    val valid = values.filter { it >= 0 }
    return if (valid.isEmpty()) stringResource(R.string.session_status_measuring) else "${valid.sum()} ms"
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
