package com.pocketrdp.feature.session

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketrdp.core.rdp.InputMode
import com.pocketrdp.core.rdp.RdpPointerFlags
import com.pocketrdp.feature.session.input.RdpInputController
import com.pocketrdp.feature.session.input.ScancodeMap
import com.pocketrdp.feature.session.render.RdpSurface
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    connectionId: Long,
    onClose: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rdpClient = viewModel.rdpClient

    val controller = remember(rdpClient) {
        RdpInputController(
            sendCursor = rdpClient::sendCursorEvent,
            sendKey = rdpClient::sendKeyEvent,
            sendUnicode = rdpClient::sendUnicodeKey,
        )
    }
    LaunchedEffect(state.mode) { controller.setMode(state.mode) }
    LaunchedEffect(state.remoteWidth, state.remoteHeight) {
        controller.setRemoteSize(state.remoteWidth, state.remoteHeight)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.connectionName.ifBlank { "会话 #$connectionId" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    StatusChip(state.status)
                    IconButton(onClick = viewModel::toggleMode) {
                        Icon(
                            imageVector = if (state.mode == InputMode.TRACKPAD) Icons.Default.Mouse else Icons.Default.TouchApp,
                            contentDescription = "切换输入模式",
                        )
                    }
                    IconButton(onClick = { viewModel.disconnect(); onClose() }) {
                        Icon(Icons.Default.Close, contentDescription = "断开")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
            )
        },
        bottomBar = {
            if (state.toolbarVisible) SessionToolbar(viewModel = viewModel, state = state)
        },
    ) { padding ->
        SessionCanvas(
            padding = padding,
            controller = controller,
            buffer = rdpClient.buffer,
        )
    }
}

@Composable
private fun StatusChip(status: SessionConnectionStatus) {
    val label = when (status) {
        SessionConnectionStatus.Idle -> "Idle"
        SessionConnectionStatus.Connecting -> "Connecting…"
        SessionConnectionStatus.Connected -> "Connected"
        is SessionConnectionStatus.Disconnected -> "Disconnected"
        is SessionConnectionStatus.Failed -> "Failed"
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun SessionCanvas(
    padding: PaddingValues,
    controller: RdpInputController,
    buffer: com.pocketrdp.core.rdp.BitmapBuffer,
) {
    val current by buffer.current.collectAsStateWithLifecycle(initialValue = null)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("等待远端帧… (M2 native 未接入)", color = Color.White)
                Text(
                    "FreeRDP submodule 与 NDK 装好后会自动出图",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    detectTapGestures(
                        onTap = { offset ->
                            controller.tap(offset.x, offset.y, size.width, size.height)
                        },
                        onLongPress = { offset ->
                            controller.longPress(offset.x, offset.y, size.width, size.height)
                        },
                    )
                }
                .pointerInput(controller) {
                    detectTransformGestures { _, pan, _, _ ->
                        if (pan.x != 0f || pan.y != 0f) {
                            controller.drag(pan.x, pan.y, 0f, 0f, size.width, size.height)
                        }
                    }
                },
            factory = { ctx ->
                RdpSurface(ctx).apply {
                    setBackgroundColor(Color.Black.toArgb())
                }
            },
            update = { surface ->
                surface.setBacking(current)
            },
        )
    }

    LaunchedEffect(buffer) {
        launch {
            buffer.dirty.collect { rect ->
                // RdpSurface inside AndroidView is updated reactively via setBacking +
                // markDirty when current swaps. Direct invalidation hook is reserved for M4
                // when we add partial blit optimisation.
                @Suppress("UNUSED_VARIABLE") val unused = rect
            }
        }
    }
}

@Composable
private fun SessionToolbar(viewModel: SessionViewModel, state: SessionUiState) {
    BottomAppBar(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarKey("Esc") { viewModel.sendKey(0x01) }
            StickyToolbarChip(
                label = "Ctrl",
                active = state.stickyModifiers and ScancodeMap.Modifier.CTRL != 0,
                onToggle = { viewModel.toggleStickyModifier(ScancodeMap.Modifier.CTRL) },
            )
            StickyToolbarChip(
                label = "Alt",
                active = state.stickyModifiers and ScancodeMap.Modifier.ALT != 0,
                onToggle = { viewModel.toggleStickyModifier(ScancodeMap.Modifier.ALT) },
            )
            StickyToolbarChip(
                label = "Shift",
                active = state.stickyModifiers and ScancodeMap.Modifier.SHIFT != 0,
                onToggle = { viewModel.toggleStickyModifier(ScancodeMap.Modifier.SHIFT) },
            )
            ToolbarKey("Win") { viewModel.sendKey(0x5B) }
            ToolbarKey("Tab") { viewModel.sendKey(0x0F) }
            ToolbarKey("CAD") { viewModel.sendCtrlAltDel() }
            IconButton(onClick = viewModel::toggleToolbar) {
                Icon(Icons.Default.Keyboard, contentDescription = "键盘")
            }
        }
    }
}

@Composable
private fun ToolbarKey(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

@Composable
private fun StickyToolbarChip(label: String, active: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onToggle,
        label = { Text(label) },
    )
}
