package com.hanfengruyue.pocketrdp.feature.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionRepository
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpClient
import com.hanfengruyue.pocketrdp.core.rdp.RdpConnectionParams
import com.hanfengruyue.pocketrdp.core.rdp.RdpEvent
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransport
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.TextInputEncoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.absoluteValue

sealed interface SessionConnectionStatus {
    data object Idle : SessionConnectionStatus
    data object Connecting : SessionConnectionStatus
    data object Connected : SessionConnectionStatus
    data class Disconnected(val reason: String?) : SessionConnectionStatus
    data class Failed(val error: String) : SessionConnectionStatus
}

data class SessionUiState(
    val connectionId: Long = 0L,
    val connectionName: String = "",
    val connectionHost: String? = null,
    val status: SessionConnectionStatus = SessionConnectionStatus.Idle,
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val mode: InputMode = InputMode.TRACKPAD,
    val systemBarsVisible: Boolean = true,
    val imeVisible: Boolean = false,
    val stickyModifiers: Int = 0,
    val connectedAtMs: Long = 0L,
    val durationSec: Long = 0L,
    val fps: Int = 0,
    /** Approximate network round-trip latency in ms; -1 = unknown / not yet measured. */
    val latencyMs: Int = -1,
    /** Negotiated network transport (TCP / RDP-UDP multitransport) for the status badge (issue #2). */
    val transport: RdpTransport = RdpTransport.UNKNOWN,
    val targetFrameRate: Int = 0,
    val lastError: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    val rdpClient: RdpClient,
    private val repository: ConnectionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val fpsCounter = FpsCounter()

    // Whether this connection opted into dynamic (auto) resolution. Loaded from the entity in
    // launchConnect. When false, we must NOT push monitor-layout PDUs — doing so resizes the
    // remote desktop even though the user disabled auto-resolution (issue: "关闭自动分辨率仍被修改").
    // Defaults true so behaviour is unchanged until the entity is loaded.
    private var dynamicResolutionEnabled: Boolean = true

    // Latest latency probe result (ms, -1 = unknown), sampled by a background coroutine and folded
    // into UI state by the 1 Hz metrics ticker (the sole writer of metrics — see CLAUDE.md).
    @Volatile private var lastLatencyMs: Int = -1

    // The last view size onSurfaceResized was called with. Used to re-emit a monitor layout
    // PDU once the RDP control channel finishes negotiating — without that the very first
    // layout we send (right after Compose measures the view) is silently dropped because the
    // dynvc/disp channel isn't open yet.
    private var pendingMonitorWidth: Int = 0
    private var pendingMonitorHeight: Int = 0
    private var monitorLayoutRetryJob: Job? = null
    // Debounce job for onSurfaceResized. Soft-keyboard show/hide animates the AndroidView's
    // height pixel-by-pixel (observed: 20+ resize callbacks over ~150 ms), and pushing a
    // DISPLAY_CONTROL_MONITOR_LAYOUT PDU on each one floods the disp channel — RDP servers
    // (or VPN middleboxes) react by closing the connection a few seconds later. The debounce
    // collapses a burst into a single PDU at the *final* stable size.
    private var monitorLayoutDebounceJob: Job? = null

    init {
        val id = savedStateHandle.get<Long>("id") ?: 0L
        _state.update { it.copy(connectionId = id) }
        observeEvents()
        startMetricsTicker()
        startLatencyProbe()
        if (id > 0L) launchConnect(id)
    }

    private fun observeEvents() {
        viewModelScope.launch {
            rdpClient.events.collect { event ->
                when (event) {
                    is RdpEvent.Connecting -> _state.update { it.copy(status = SessionConnectionStatus.Connecting) }
                    is RdpEvent.Connected -> {
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Connected,
                                connectedAtMs = System.currentTimeMillis(),
                                lastError = null,
                            )
                        }
                        // Start hammering monitor layout until the server actually responds.
                        // OnConnectionSuccess only means the main RDP connection finished
                        // negotiating; the DRDYNVC sub-channel that carries Display Control
                        // PDUs comes up later (observed: ~9 s in the field). Until then
                        // android_disp_send_monitor_layout returns FALSE and the PDU is
                        // dropped on the floor.
                        // ONLY when auto-resolution is enabled — otherwise sending a monitor
                        // layout would resize the remote desktop against the user's setting.
                        if (dynamicResolutionEnabled) scheduleMonitorLayoutRetry()
                    }
                    is RdpEvent.Disconnected -> {
                        monitorLayoutRetryJob?.cancel()
                        monitorLayoutDebounceJob?.cancel()
                        fpsCounter.reset()
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Disconnected(event.reason),
                                connectedAtMs = 0L,
                                durationSec = 0L,
                                fps = 0,
                            )
                        }
                    }
                    is RdpEvent.Failed -> {
                        monitorLayoutRetryJob?.cancel()
                        monitorLayoutDebounceJob?.cancel()
                        fpsCounter.reset()
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Failed(event.error),
                                connectedAtMs = 0L,
                                durationSec = 0L,
                                fps = 0,
                                lastError = event.error,
                            )
                        }
                    }
                    is RdpEvent.GraphicsResized -> {
                        _state.update { it.copy(remoteWidth = event.width, remoteHeight = event.height) }
                        // The server occasionally clamps to multiples of 2 (we've seen 1278
                        // come back from a 1279 request, 3038 from 3039), so tolerate small
                        // diffs when deciding "matched, stop retrying".
                        val pw = pendingMonitorWidth
                        val ph = pendingMonitorHeight
                        if (pw > 0 && ph > 0 &&
                            (event.width - pw).absoluteValue <= 8 &&
                            (event.height - ph).absoluteValue <= 8) {
                            PocketLogger.i(TAG, "monitor layout acknowledged (remote=${event.width}x${event.height})")
                            monitorLayoutRetryJob?.cancel()
                        }
                    }
                    // GraphicsUpdated (content-driven, 30-60 Hz and 0 when idle) is no longer
                    // counted for FPS. The counter is now ticked from RdpSurface.onFrameRendered
                    // (see onFrameRendered below) so the displayed number reflects the steady
                    // render rate / configured target frame rate instead of dropping to 0 the
                    // moment the remote screen stops changing.
                    else -> Unit
                }
            }
        }
    }

    /** 1 Hz ticker: pulls fps + connection duration into UI state. */
    private fun startMetricsTicker() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val current = _state.value
                if (current.status is SessionConnectionStatus.Connected && current.connectedAtMs > 0L) {
                    val now = System.currentTimeMillis()
                    val dur = (now - current.connectedAtMs) / 1000L
                    // transportInfo() is a cheap in-memory native getter; the RDP-UDP multitransport
                    // bootstrap finishes a few seconds after Connected, so polling each tick is how
                    // the badge flips TCP→UDP once it comes up.
                    val transport = decodeTransport(rdpClient.transportInfo())
                    _state.update {
                        it.copy(fps = fpsCounter.snapshot(), durationSec = dur, latencyMs = lastLatencyMs, transport = transport)
                    }
                } else if (current.fps != 0 || current.durationSec != 0L || current.latencyMs != -1 ||
                    current.transport != RdpTransport.UNKNOWN) {
                    _state.update { it.copy(fps = 0, durationSec = 0L, latencyMs = -1, transport = RdpTransport.UNKNOWN) }
                }
            }
        }
    }

    /**
     * Background latency probe: every few seconds, measure an approximate RTT to the RDP host and
     * cache it in [lastLatencyMs]. The 1 Hz [startMetricsTicker] is the sole writer of the value
     * into UI state, so this never recomposes the TopAppBar at probe frequency.
     */
    private fun startLatencyProbe() {
        viewModelScope.launch {
            while (isActive) {
                lastLatencyMs = if (_state.value.status is SessionConnectionStatus.Connected) {
                    rdpClient.measureLatencyMs()
                } else {
                    -1
                }
                delay(LATENCY_PROBE_INTERVAL_MS)
            }
        }
    }

    /**
     * Invoked by RdpSurface once per actually-rendered frame (always on the UI thread). This —
     * not the content-update event — drives the FPS counter, so the value the 1 Hz ticker
     * snapshots is the steady render rate (target frame rate clamped to the screen refresh
     * rate), not the bursty/zero-when-idle content rate.
     */
    fun onFrameRendered() = fpsCounter.tick()

    /** Decode the native [RdpClient.transportInfo] bitfield: -1 → UNKNOWN, bit0 set → UDP, else TCP. */
    private fun decodeTransport(raw: Int): RdpTransport = when {
        raw < 0 -> RdpTransport.UNKNOWN
        raw and 0x1 != 0 -> RdpTransport.UDP
        else -> RdpTransport.TCP
    }

    private fun launchConnect(id: Long) {
        viewModelScope.launch {
            val entity = repository.findById(id) ?: run {
                PocketLogger.e(TAG, "connection id=$id not found in repository")
                _state.update {
                    it.copy(
                        status = SessionConnectionStatus.Failed("connection not found"),
                        lastError = "connection not found",
                    )
                }
                return@launch
            }
            PocketLogger.i(TAG, "launchConnect id=$id name='${entity.name}' host=${entity.host}:${entity.port}")
            // Resolution: a custom fixed size (issue 自定义分辨率) pins the remote desktop and is
            // mutually exclusive with dynamic-resolution (we must NOT push monitor-layout PDUs that
            // would resize it). Otherwise fall back to the dynamic-res default of 1920×1080 initial.
            val useCustomRes = entity.customWidth > 0 && entity.customHeight > 0
            val effectiveDynamic = entity.dynamicResolution && !useCustomRes
            val initialW = if (useCustomRes) entity.customWidth else 1920
            val initialH = if (useCustomRes) entity.customHeight else 1080
            // Cache the auto-resolution setting for the session lifetime so the monitor-layout
            // sends below are correctly gated even if the entity changes later.
            dynamicResolutionEnabled = effectiveDynamic
            _state.update {
                it.copy(
                    connectionName = entity.name,
                    connectionHost = "${entity.host}:${entity.port}",
                    targetFrameRate = entity.targetFrameRate,
                    // Open in the connection's preferred input mode (1 = 直接触屏 / native touch).
                    mode = if (entity.defaultInputMode == 1) InputMode.TOUCH else InputMode.TRACKPAD,
                )
            }
            val plain = runCatching { repository.decryptPassword(entity) }.getOrDefault("")
            val params = RdpConnectionParams(
                connectionId = entity.id,
                host = entity.host,
                port = entity.port,
                username = entity.username,
                domain = entity.domain,
                password = plain,
                colorDepth = entity.colorDepth,
                useH264 = entity.useH264,
                useGfx = entity.useGfx,
                dynamicResolution = effectiveDynamic,
                useMultitransport = entity.useMultitransport,
                redirectClipboard = entity.redirectClipboard,
                redirectFiles = entity.redirectFiles,
                sharedFolderUri = entity.sharedFolderUri,
                soundMode = entity.soundMode,
                desktopScaleFactor = entity.desktopScaleFactor,
                initialWidth = initialW,
                initialHeight = initialH,
                acceptedCertThumbprint = entity.certThumbSha256,
            )
            rdpClient.connect(params)
            repository.touchLastUsed(entity.id)
        }
    }

    fun toggleMode() {
        _state.update {
            it.copy(mode = if (it.mode == InputMode.TRACKPAD) InputMode.TOUCH else InputMode.TRACKPAD)
        }
    }

    /** Enter / exit immersive full-screen: hide system bars AND the app's top/bottom bars. */
    fun toggleImmersive() {
        _state.update {
            val newVisible = !it.systemBarsVisible
            // Leaving immersive shouldn't pop the keyboard/toolbar back; entering hides them.
            it.copy(systemBarsVisible = newVisible, imeVisible = if (newVisible) it.imeVisible else false)
        }
    }

    fun setImeVisible(visible: Boolean) {
        _state.update { it.copy(imeVisible = visible) }
    }

    fun toggleIme() {
        _state.update { it.copy(imeVisible = !it.imeVisible) }
    }

    /**
     * Toggle a sticky modifier. Unlike a one-shot tap, the keydown/keyup are split: pressing
     * "Ctrl" once latches Ctrl-down, pressing again releases. This lets the user assemble
     * combos like Ctrl+Shift+Esc with on-screen taps.
     */
    fun toggleStickyModifier(flag: Int) {
        val vk = ScancodeMap.Modifier.vkFor(flag) ?: return
        val currentMask = _state.value.stickyModifiers
        val newMask = currentMask xor flag
        val isDown = (newMask and flag) != 0
        rdpClient.sendKeyEvent(vk, isDown)
        _state.update { it.copy(stickyModifiers = newMask) }
    }

    /** Send a Windows virtual-key code as a tap (down then up). */
    fun sendKey(vk: Int) {
        rdpClient.sendKeyEvent(vk, true)
        rdpClient.sendKeyEvent(vk, false)
    }

    /** Forward a discrete VK down/up — used by the IME bridge's onPreviewKeyEvent path. */
    fun sendVkRaw(vk: Int, down: Boolean) {
        rdpClient.sendKeyEvent(vk, down)
    }

    fun sendCtrlAltDel() {
        val ctrl = ScancodeMap.VK.LCONTROL
        val alt = ScancodeMap.VK.LMENU
        val del = ScancodeMap.VK.DELETE
        rdpClient.sendKeyEvent(ctrl, true)
        rdpClient.sendKeyEvent(alt, true)
        rdpClient.sendKeyEvent(del, true)
        rdpClient.sendKeyEvent(del, false)
        rdpClient.sendKeyEvent(alt, false)
        rdpClient.sendKeyEvent(ctrl, false)
    }

    fun typeText(text: String) {
        // Route per-character: printable ASCII via the scancode path (works regardless of the
        // server's unicode-input capability), other code points via unicode only when the server
        // supports it. Sending unicode to a server that didn't negotiate it tears down the whole
        // session — this is the "type a character → instant disconnect" fix.
        TextInputEncoder.type(
            text = text,
            unicodeSupported = rdpClient.isUnicodeInputSupported(),
            sendKey = rdpClient::sendKeyEvent,
            sendUnicode = rdpClient::sendUnicodeKey,
        )
    }

    fun onSurfaceResized(width: Int, height: Int) {
        // Auto-resolution disabled → never push a monitor layout; the remote stays at its connect
        // resolution regardless of how the local view is sized (issue: "关闭自动分辨率仍被修改").
        if (!dynamicResolutionEnabled) return
        // Always update the pending size synchronously — scheduleMonitorLayoutRetry reads it
        // and we need the *latest* dimensions if a retry happens to fire before the debounce.
        pendingMonitorWidth = width
        pendingMonitorHeight = height
        // Collapse keyboard-animation resize bursts. We log only the post-debounce dispatch so
        // logs stay readable; the burst itself produces no spam.
        monitorLayoutDebounceJob?.cancel()
        monitorLayoutDebounceJob = viewModelScope.launch {
            delay(MONITOR_LAYOUT_DEBOUNCE_MS)
            // Don't resize the remote just because the soft keyboard shrank the local viewport —
            // wait until the keyboard is dismissed and the view returns to its full size.
            if (_state.value.imeVisible) return@launch
            val w = pendingMonitorWidth
            val h = pendingMonitorHeight
            if (w <= 0 || h <= 0) return@launch
            PocketLogger.d(TAG, "onSurfaceResized $w x $h (debounced) — sending monitor layout")
            val ok = rdpClient.sendMonitorLayout(w, h)
            if (!ok && _state.value.status == SessionConnectionStatus.Connected) {
                // Connected but disp channel still negotiating. Kick off the retry loop so
                // the layout eventually lands.
                scheduleMonitorLayoutRetry()
            }
        }
    }

    /**
     * Repeatedly resend the latest monitor layout until the server emits a matching
     * OnGraphicsResize (acknowledgement) or we exhaust the backoff schedule. Cancelled
     * automatically on Disconnected / Failed / acknowledged in observeEvents.
     */
    private fun scheduleMonitorLayoutRetry() {
        monitorLayoutRetryJob?.cancel()
        monitorLayoutRetryJob = viewModelScope.launch {
            // Backoff schedule: 0, 0.5, 1, 2, 3, 5, 5, 5, 5 s — cumulative ~21 s, which
            // covers the worst case we've observed in the field (~9 s) with margin.
            val delaysMs = longArrayOf(0, 500, 1000, 2000, 3000, 5000, 5000, 5000, 5000)
            for ((idx, ms) in delaysMs.withIndex()) {
                delay(ms)
                val w = pendingMonitorWidth
                val h = pendingMonitorHeight
                if (w <= 0 || h <= 0) continue
                val ok = rdpClient.sendMonitorLayout(w, h)
                PocketLogger.i(TAG, "monitor-layout retry #$idx ${w}x$h (after ${ms}ms) ok=$ok")
                // Don't break on ok==true: even if the JNI accepted it, the server might
                // still drop it (PDU sent before its own disp client finished init). We
                // stop only when the matching OnGraphicsResize comes back.
            }
            PocketLogger.w(TAG, "monitor-layout retry schedule exhausted; server never acknowledged")
        }
    }

    fun disconnect() {
        rdpClient.disconnect()
    }

    override fun onCleared() {
        // M7: keep connection alive via Foreground Service. For now (M2), disconnect on dispose.
        PocketLogger.i(TAG, "SessionViewModel.onCleared — disconnecting")
        rdpClient.disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Session"
        // 150 ms covers the soft-keyboard show/hide animation (typically 250-300 ms total,
        // with 20-30 resize ticks bunched in the first ~150 ms). Long enough to coalesce a
        // burst, short enough that orientation changes still feel responsive (the rotation
        // resize lands once, well before this timeout).
        private const val MONITOR_LAYOUT_DEBOUNCE_MS = 150L
        // How often the background coroutine probes network latency. 4 s balances freshness
        // against the cost of opening a throwaway TCP connection to the host.
        private const val LATENCY_PROBE_INTERVAL_MS = 4000L
    }
}
