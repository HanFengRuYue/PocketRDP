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
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
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
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransport
import com.hanfengruyue.pocketrdp.feature.session.SessionConnectionStatus

/**
 * Compact session-status indicator for the TopAppBar title slot.
 *
 * Renders as a single row:  [●] 8.163.3.48:41120 ⏷
 * Tapping anywhere on the row opens a dropdown with the full set of session metrics
 * (resolution, duration, FPS, mode, sticky modifiers, last error).
 *
 * This replaces the older "stuff every chip into TopAppBar.actions" layout, which on a
 * 360-400dp portrait screen pushed the title off-screen and overlapped the status bar.
 */
@Composable
fun SessionStatusTitle(
    status: SessionConnectionStatus,
    connectionName: String,
    connectionId: Long,
    remoteWidth: Int,
    remoteHeight: Int,
    durationSec: Long,
    fps: Int,
    latencyMs: Int,
    controlLatencyMs: Int,
    transport: RdpTransport,
    mode: InputMode,
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
                    text = { Text("已连接：${formatDuration(durationSec)}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Timer) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("帧率：${fps} fps", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Speed) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "操控延迟：${if (controlLatencyMs >= 0) "$controlLatencyMs ms" else "测量中…"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.Bolt) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "网络往返：${if (latencyMs >= 0) "$latencyMs ms" else "测量中…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = { MenuRowIcon(Icons.Default.Wifi) },
                    onClick = { expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("网络协议：${transportLabel(transport)}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { MenuRowIcon(Icons.Default.Lan) },
                    onClick = { expanded = false },
                )
            }
            DropdownMenuItem(
                text = { Text("输入模式：${if (mode == InputMode.TRACKPAD) "模拟鼠标" else "直接触屏"}", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = {
                    MenuRowIcon(if (mode == InputMode.TRACKPAD) Icons.Default.Mouse else Icons.Default.TouchApp)
                },
                onClick = { expanded = false },
            )
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
    RdpTransport.UDP -> "UDP（多路传输）"
    RdpTransport.TCP -> "TCP"
    RdpTransport.UNKNOWN -> "检测中…"
}

private fun statusFullLabel(status: SessionConnectionStatus): String = when (status) {
    SessionConnectionStatus.Idle -> "未连接"
    SessionConnectionStatus.Connecting -> "正在连接…"
    SessionConnectionStatus.Connected -> "已连接"
    is SessionConnectionStatus.Disconnected -> "已断开 (${status.reason ?: "—"})"
    is SessionConnectionStatus.Failed -> "连接失败"
}

private fun formatDuration(sec: Long): String {
    if (sec < 0) return "00:00"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
