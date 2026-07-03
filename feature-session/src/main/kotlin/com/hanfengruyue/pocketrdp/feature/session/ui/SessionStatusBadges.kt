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
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransport
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportStats
import com.hanfengruyue.pocketrdp.feature.session.SessionConnectionStatus
import java.util.Locale

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
    onErrorClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val titleText = connectionName.ifBlank { "会话 #$connectionId" }
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
                contentDescription = "展开会话信息",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status)
                        Spacer(Modifier.width(8.dp))
                        Text(statusFullLabel(status), style = MaterialTheme.typography.labelLarge)
                    }
                },
                onClick = { expanded = false },
            )
            if (!host.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("主机：$host", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Dns) },
                    onClick = { expanded = false },
                )
            }
            if (remoteWidth > 0 && remoteHeight > 0) {
                DropdownMenuItem(
                    text = { Text("远端分辨率：${remoteWidth}×${remoteHeight}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.AspectRatio) },
                    onClick = { expanded = false },
                )
            }
            if (status is SessionConnectionStatus.Connected) {
                DropdownMenuItem(
                    text = { Text("帧率：${fps} fps", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Speed) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "操控延迟（输入→解码）：${if (controlLatencyMs >= 0) "$controlLatencyMs ms" else "测量中…"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.Bolt) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "显示延迟（解码→上屏）：${if (presentLagMs >= 0) "$presentLagMs ms" else "测量中…"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.Schedule) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "网络往返：${if (networkRttMs >= 0) "$networkRttMs ms" else "测量中…"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.NetworkCheck) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "采样：接受 $latencyAccepted / 丢弃 $latencyDiscarded",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.DataUsage) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("网络协议：${transportLabel(transport)}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Lan) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "UDP 流量：↓ ${formatBytes(transportStats.inBytes)} / ↑ ${formatBytes(transportStats.outBytes)}，" +
                                "包 ↓ ${transportStats.inPackets} / ↑ ${transportStats.outPackets}，重传 ${transportStats.retransmits}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.DataUsage) },
                    onClick = { expanded = false },
                )
                if (transport == RdpTransport.TCP_FALLBACK) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "UDP 回退原因：${udpFallbackReason(transportStats)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingIcon = { MenuRowIcon(Icons.Default.ErrorOutline) },
                        onClick = { expanded = false },
                    )
                }
            }
            if (stickyModifierLabels.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("粘滞修饰键：${stickyModifierLabels.joinToString("+")}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Keyboard) },
                    onClick = { expanded = false },
                )
            }
            if (!lastError.isNullOrBlank()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "最近错误：$lastError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
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
private fun MenuRowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

private fun transportLabel(transport: RdpTransport): String = when (transport) {
    RdpTransport.TCP -> "TCP"
    RdpTransport.TCP_FALLBACK -> "TCP（UDP 回退）"
    RdpTransport.UDP_R -> "UDP-R"
    RdpTransport.UDP_L -> "UDP-L"
    RdpTransport.UDP2 -> "UDP2"
    RdpTransport.UNKNOWN -> "检测中…"
}

private fun udpFallbackReason(stats: RdpTransportStats): String {
    val stage = when (stats.failureStage.toInt()) {
        1 -> "协议类型未支持"
        2 -> "RDP 目标地址无效"
        3 -> "UDP socket 连接失败"
        4 -> "UDP SYN 发送失败"
        5 -> "UDP SYN 无响应；检查 frp/防火墙 UDP 映射"
        6 -> "UDP SYN-ACK 接收失败"
        7 -> "UDP SYN-ACK 解析失败"
        8 -> "本地资源不足"
        9 -> "UDP final ACK 发送失败"
        10 -> "服务端 UDP 版本不支持"
        11 -> "UDP TLS/DTLS 握手失败"
        12 -> "Tunnel Create Request 发送失败"
        13 -> "Tunnel Create Response 无响应"
        14 -> "服务端拒绝 Tunnel Create"
        else -> "服务端未建立 UDP tunnel"
    }
    val socket = if (stats.socketError != 0L) "，socket=${stats.socketError}" else ""
    val hr = if (stats.tunnelHr != 0L) "，HRESULT=${formatHr(stats.tunnelHr)}" else ""
    return "$stage$hr$socket"
}

private fun formatHr(value: Long): String = String.format(Locale.US, "0x%08X", value)

private fun statusFullLabel(status: SessionConnectionStatus): String = when (status) {
    SessionConnectionStatus.Idle -> "未连接"
    SessionConnectionStatus.Connecting -> "正在连接…"
    SessionConnectionStatus.Connected -> "已连接"
    is SessionConnectionStatus.Disconnected -> "已断开 (${status.reason ?: "—"})"
    is SessionConnectionStatus.Failed -> "连接失败"
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value >= 1_048_576L -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
        value >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }
}
