package com.pocketrdp.feature.session.input

import com.pocketrdp.core.rdp.InputMode
import com.pocketrdp.core.rdp.RdpPointerFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Maps Compose pointer events into RDP cursor / keyboard wire-protocol calls.
 *
 * Stateless w.r.t. the native client (it just calls sendCursorEvent / sendKeyEvent), so this
 * class can be unit-tested without a running RDP session.
 */
class RdpInputController(
    private val sendCursor: (x: Int, y: Int, flags: Int) -> Unit,
    private val sendKey: (scanCode: Int, down: Boolean) -> Unit,
    private val sendUnicode: (codePoint: Int, down: Boolean) -> Unit,
) {
    private val _mode = MutableStateFlow(InputMode.TRACKPAD)
    val mode: StateFlow<InputMode> = _mode.asStateFlow()

    private var virtualX: Float = 0f
    private var virtualY: Float = 0f
    private var remoteWidth: Int = 1
    private var remoteHeight: Int = 1
    private var stickyModifiers: Int = 0

    fun setRemoteSize(width: Int, height: Int) {
        remoteWidth = width.coerceAtLeast(1)
        remoteHeight = height.coerceAtLeast(1)
        virtualX = virtualX.coerceIn(0f, remoteWidth - 1f)
        virtualY = virtualY.coerceIn(0f, remoteHeight - 1f)
    }

    fun toggleMode() {
        _mode.value = if (_mode.value == InputMode.TRACKPAD) InputMode.TOUCH else InputMode.TRACKPAD
    }

    fun setMode(mode: InputMode) {
        _mode.value = mode
    }

    fun tap(localX: Float, localY: Float, viewW: Int, viewH: Int) {
        when (_mode.value) {
            InputMode.TRACKPAD -> {
                sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
                sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON1)
            }
            InputMode.TOUCH -> {
                val (rx, ry) = toRemote(localX, localY, viewW, viewH)
                sendCursor(rx, ry, RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
                sendCursor(rx, ry, RdpPointerFlags.BUTTON1)
            }
        }
    }

    fun longPress(localX: Float, localY: Float, viewW: Int, viewH: Int) {
        val (rx, ry) = if (_mode.value == InputMode.TRACKPAD) {
            virtualX.roundToInt() to virtualY.roundToInt()
        } else {
            toRemote(localX, localY, viewW, viewH)
        }
        sendCursor(rx, ry, RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON2)
        sendCursor(rx, ry, RdpPointerFlags.BUTTON2)
    }

    fun drag(dx: Float, dy: Float, localX: Float, localY: Float, viewW: Int, viewH: Int) {
        when (_mode.value) {
            InputMode.TRACKPAD -> {
                val accel = if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 25f) 1.6f else 1.0f
                virtualX = (virtualX + dx * accel).coerceIn(0f, remoteWidth - 1f)
                virtualY = (virtualY + dy * accel).coerceIn(0f, remoteHeight - 1f)
                sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.MOVE)
            }
            InputMode.TOUCH -> {
                val (rx, ry) = toRemote(localX, localY, viewW, viewH)
                sendCursor(rx, ry, RdpPointerFlags.MOVE or RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
            }
        }
    }

    fun scroll(deltaY: Float) {
        val wheelDelta = (-deltaY / 32f).toInt().coerceIn(-127, 127)
        val flags = RdpPointerFlags.WHEEL or (wheelDelta and 0xFF) or
            (if (wheelDelta < 0) RdpPointerFlags.WHEEL_NEGATIVE else 0)
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), flags)
    }

    fun typeText(text: String) {
        text.forEach { ch ->
            sendUnicode(ch.code, true)
            sendUnicode(ch.code, false)
        }
    }

    fun pressControlKey(scanCode: Int) {
        sendKey(scanCode, true)
        sendKey(scanCode, false)
    }

    fun ctrlAltDel() {
        val ctrl = 0x1D
        val alt = 0x38
        val del = 0x53
        sendKey(ctrl, true)
        sendKey(alt, true)
        sendKey(del, true)
        sendKey(del, false)
        sendKey(alt, false)
        sendKey(ctrl, false)
    }

    fun toggleStickyModifier(flag: Int) {
        stickyModifiers = stickyModifiers xor flag
    }

    private fun toRemote(localX: Float, localY: Float, viewW: Int, viewH: Int): Pair<Int, Int> {
        val rx = (localX / viewW.coerceAtLeast(1) * remoteWidth).toInt().coerceIn(0, remoteWidth - 1)
        val ry = (localY / viewH.coerceAtLeast(1) * remoteHeight).toInt().coerceIn(0, remoteHeight - 1)
        return rx to ry
    }
}
