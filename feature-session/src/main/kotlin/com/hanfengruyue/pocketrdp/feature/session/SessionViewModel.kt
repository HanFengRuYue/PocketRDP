package com.hanfengruyue.pocketrdp.feature.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionRepository
import com.hanfengruyue.pocketrdp.core.data.thumbnail.ConnectionThumbnailStore
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpClient
import com.hanfengruyue.pocketrdp.core.rdp.RdpConnectionParams
import com.hanfengruyue.pocketrdp.core.rdp.RdpEvent
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransport
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportStats
import com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.TextInputEncoder
import com.hanfengruyue.pocketrdp.feature.session.service.RdpSessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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
    /**
     * Whether the app's own top bar (chrome) is shown. This NO LONGER controls the Android system
     * status/navigation bars — those are kept hidden for the whole session (see SessionScreen) so
     * the remote picture reclaims the space the system status bar used to occupy. Toggled by the
     * full-screen button: false = pure picture (only a small exit FAB).
     */
    val chromeVisible: Boolean = true,
    val imeVisible: Boolean = false,
    val stickyModifiers: Int = 0,
    val connectedAtMs: Long = 0L,
    val durationSec: Long = 0L,
    val fps: Int = 0,
    /** Approximate network round-trip latency in ms (TCP handshake); -1 = unknown / not yet measured. */
    val latencyMs: Int = -1,
    /**
     * End-to-end control latency in ms (input → on-screen change), empirically including server
     * processing + encode + network + client decode — the lag the user actually feels. -1 until the
     * first sample. See [RdpClient.controlLatencyMs].
     */
    val controlLatencyMs: Int = -1,
    /**
     * Display-pipeline latency in ms (decode→on-screen present) — the part [controlLatencyMs] does NOT
     * measure. Felt latency ≈ controlLatencyMs + presentLagMs. -1 until the first drawn frame.
     */
    val presentLagMs: Int = -1,
    /** Diagnostic (METRIC-4): discrete-input samples that got a timely frame (accepted) vs none (discarded). */
    val latencyAccepted: Int = 0,
    val latencyDiscarded: Int = 0,
    /** Negotiated network transport (TCP / RDP-UDP multitransport) for the status badge (issue #2). */
    val transport: RdpTransport = RdpTransport.UNKNOWN,
    val transportStats: RdpTransportStats = RdpTransportStats(),
    val targetFrameRate: Int = 0,
    /**
     * Whether this connection has folder redirection on (整盘共享). When true, SessionScreen prompts
     * for the MANAGE_EXTERNAL_STORAGE (所有文件访问) permission if it isn't granted yet — without it the
     * remote "PocketRDP" drive shows empty. Loaded from entity.redirectFiles in launchConnect.
     */
    val filesRedirectEnabled: Boolean = false,
    val allFilesAccessRequired: Boolean = false,
    val lastError: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    val rdpClient: RdpClient,
    private val repository: ConnectionRepository,
    private val thumbnailStore: ConnectionThumbnailStore,
    @ApplicationContext private val appContext: Context,
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

    // Max remote-resolution cap (SHORT-edge px) applied to every monitor-layout PDU while dynamic
    // resolution is on, so we never ask the remote to render at the phone's full (often 1440p+) native
    // resolution (issue: 直接套用手机分辨率导致性能压力过大). 0 = 跟随设备 / no cap. Loaded from
    // entity.dynamicResMax in launchConnect; only meaningful while [dynamicResolutionEnabled].
    private var dynamicResMaxShortEdge: Int = 0

    // Clipboard text bridge. The cliprdr channel is enabled at the protocol layer via
    // /clipboard:...direction-to:all in buildCommandLine, but the actual sync to/from the Android
    // system clipboard lives here — the channel alone does nothing (field bug: 剪贴板双向同步开关无效).
    // Gated on the connection's redirectClipboard, loaded in launchConnect. Text only (the JNI /
    // cliprdr path is plain-string; images would need a native rebuild).
    private var clipboardSyncEnabled: Boolean = false
    // Local→remote clipboard push (phone copy → PC) is now ENABLED. The libfreerdp-android.so was
    // cleanly rebuilt in WSL with the EVENT_TYPE_CLIPBOARD non-fatal patch (android_event.c null-checks
    // afc->cliprdr and forces rc=TRUE, like the TOUCH/KEY cases), so a clipboard send before the cliprdr
    // channel is up — or when the server declined clipboard redirection — is logged and dropped instead
    // of breaking the main loop → disconnect (the old 手机端复制后连接几秒内断开 bug). The rebuild changed
    // ONLY libfreerdp-android.so (the deps stayed byte-identical to the field-tested binaries), so there
    // is no ABI mismatch with libfreerdp3.so (the earlier incremental-rebuild black-screen cause).
    private val localToRemoteClipboardEnabled = true
    // De-dupe token shared by BOTH directions to break the echo loop: remote→local sets it before
    // setPrimaryClip (so our own OnPrimaryClipChanged sees an unchanged value and skips), and
    // local→remote sets it before sendClipboard (so the server echoing the same text back as
    // ClipboardReceived is ignored). Without it the two directions ping-pong the same text forever.
    @Volatile private var lastSyncedClipboard: String = ""
    private val clipboardManager: ClipboardManager by lazy {
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private var clipListenerRegistered = false
    private var clipDebounceJob: Job? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!clipboardSyncEnabled) return@OnPrimaryClipChangedListener
        // Debounce + off-main-thread + length cap (the 复制大量文本→断开/闪退 fix). The system can fire
        // this several times in a burst AND on the main thread; reading the clip and JNI-sending a huge
        // string synchronously on each callback blocked the UI and could overwhelm the cliprdr channel.
        // Collapse a burst into ONE send of the final clip, on a background coroutine, capped length.
        clipDebounceJob?.cancel()
        clipDebounceJob = viewModelScope.launch(Dispatchers.Default) {
            delay(CLIPBOARD_DEBOUNCE_MS)
            val raw = clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(appContext)
                ?.toString()
                .orEmpty()
            // Skip empties and anything we just received from the remote (loop guard, compared on the
            // RAW clip so an over-cap remote paste echoed back here still matches and isn't bounced).
            if (raw.isEmpty() || raw == lastSyncedClipboard) return@launch
            lastSyncedClipboard = raw
            val txt = if (raw.length > CLIPBOARD_MAX_CHARS) {
                PocketLogger.w(TAG, "clipboard ${raw.length} chars > cap $CLIPBOARD_MAX_CHARS, truncating")
                raw.take(CLIPBOARD_MAX_CHARS)
            } else {
                raw
            }
            rdpClient.sendClipboard(txt)
        }
    }

    // Large-text input queue (the 粘贴大量文本→断开/闪退 fix). typeText offers each committed chunk here;
    // a SINGLE background consumer coroutine (startTypeConsumer) types it via TextInputEncoder.typeThrottled
    // so keystrokes are paced (no UI block, no input-channel flood) AND ordered across overlapping pastes.
    private val typeChannel = Channel<String>(Channel.UNLIMITED)

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

    // --- Background keep-alive + auto-reconnect (M7) ---
    // The keep-alive RdpSessionService keeps the PROCESS alive while backgrounded (the connection
    // itself lives in the @Singleton RdpClient). We start it when a connection begins and stop it
    // when the user tears the session down or we exhaust reconnect attempts.
    //
    // Reconnect distinguishes intent via the Disconnected event's reason: RdpClient.disconnect()
    // emits reason="user" (deliberate — don't reconnect), while a native/network drop emits
    // reason=null (unexpected — reconnect with backoff). A failed connect attempt (RdpEvent.Failed)
    // is also retried unless the teardown was user-initiated.
    private var lastParams: RdpConnectionParams? = null
    private var lastConnName: String = ""
    private var lastHostLabel: String = ""
    private var userInitiatedDisconnect: Boolean = false
    private var reconnectAttempt: Int = 0
    private var reconnectJob: Job? = null

    init {
        val id = savedStateHandle.get<Long>("id") ?: 0L
        _state.update { it.copy(connectionId = id) }
        observeEvents()
        startMetricsTicker()
        startLatencyProbe()
        startThumbnailCapture()
        startTypeConsumer()
        if (id > 0L) launchConnect(id)
    }

    fun ensureStarted(connectionId: Long) {
        if (connectionId <= 0L) return
        val current = _state.value
        if (current.connectionId == connectionId && current.status !is SessionConnectionStatus.Idle &&
            !current.allFilesAccessRequired
        ) return
        _state.update { it.copy(connectionId = connectionId) }
        launchConnect(connectionId)
    }

    fun retryAfterAllFilesAccess() {
        val current = _state.value
        if (!current.allFilesAccessRequired || !Environment.isExternalStorageManager()) return
        _state.update { it.copy(allFilesAccessRequired = false, lastError = null) }
        launchConnect(current.connectionId)
    }

    /**
     * Single ordered consumer of [typeChannel]: types each committed text chunk on a background
     * thread, paced by [TextInputEncoder.typeThrottled]. One consumer (not one coroutine per chunk)
     * keeps keystroke order correct when pastes overlap. Re-reads unicode support per chunk (a cheap
     * native getter) so it tracks the post-connect capability flip.
     */
    private fun startTypeConsumer() {
        viewModelScope.launch(Dispatchers.Default) {
            for (text in typeChannel) {
                runCatching {
                    TextInputEncoder.typeThrottled(
                        text = text,
                        unicodeSupported = rdpClient.isUnicodeInputSupported(),
                        sendKey = rdpClient::sendKeyEvent,
                        sendUnicode = rdpClient::sendUnicodeKey,
                    )
                }
            }
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            rdpClient.events.collect { event ->
                when (event) {
                    is RdpEvent.Connecting -> _state.update { it.copy(status = SessionConnectionStatus.Connecting) }
                    is RdpEvent.Connected -> {
                        // A live connection: clear reconnect bookkeeping so a later drop starts its
                        // backoff from zero. On the INITIAL connect (not a reconnect) the app is in
                        // the foreground, so it's safe to refresh the FGS notification to "已连接"
                        // here — this also covers the race where a very fast Connected fired before
                        // the service subscribed to events. On a *reconnect* we skip it (the app may
                        // be backgrounded → API 31+ FGS background-start restriction; the already-
                        // subscribed service updates its own notification from this event instead).
                        val wasReconnect = reconnectAttempt > 0
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        if (!wasReconnect) startKeepAlive(appContext.getString(R.string.session_notification_status_connected))
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
                        // Start watching the local clipboard so copies on the phone reach the remote.
                        registerClipboardListener()
                    }
                    is RdpEvent.ClipboardReceived -> {
                        // Remote → local. Mirror the de-dupe token first so our own
                        // OnPrimaryClipChanged (fired by setPrimaryClip) recognises this as an
                        // echo and doesn't bounce it back to the server.
                        if (clipboardSyncEnabled && event.text.isNotEmpty() && event.text != lastSyncedClipboard) {
                            lastSyncedClipboard = event.text
                            clipboardManager.setPrimaryClip(ClipData.newPlainText("PocketRDP", event.text))
                        }
                    }
                    is RdpEvent.Disconnected -> {
                        monitorLayoutRetryJob?.cancel()
                        monitorLayoutDebounceJob?.cancel()
                        unregisterClipboardListener()
                        fpsCounter.reset()
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Disconnected(event.reason),
                                connectedAtMs = 0L,
                                durationSec = 0L,
                                fps = 0,
                            )
                        }
                        // reason=="user" → deliberate teardown (disconnect button / left screen):
                        // the service self-stops, just cancel any pending reconnect. reason==null →
                        // an unexpected native/network drop: auto-reconnect with backoff.
                        if (event.reason == "user") {
                            reconnectJob?.cancel()
                        } else {
                            scheduleReconnect()
                        }
                    }
                    is RdpEvent.Failed -> {
                        monitorLayoutRetryJob?.cancel()
                        monitorLayoutDebounceJob?.cancel()
                        unregisterClipboardListener()
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
                        // A failed attempt (incl. mid-session reconnect) is retried with backoff
                        // unless the user tore the session down.
                        scheduleReconnect()
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
                    is RdpEvent.GraphicsUpdated -> {
                        // FPS = the genuine CONTENT update rate (how often the remote picture
                        // actually changed), counted here per published frame. This deliberately
                        // REVERTS the earlier "count render frames via onFrameRendered" design:
                        // that counted the self-redraw loop, which in 自动/auto mode (targetFrameRate
                        // == 0) runs at the device refresh rate (e.g. 120 Hz) and reported ~120 fps
                        // even when the remote screen barely changed (用户反馈: 帧数虚高，肉眼根本没那么
                        // 高). Counting content frames matches what the eye sees — idle ⇒ ~0, video ⇒
                        // ~content fps — and is honest. The 1 Hz ticker still samples snapshot().
                        fpsCounter.tick()
                    }
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
                    // transportInfo() is a cheap in-memory native getter. Polling each tick lets
                    // the badge flip only when a real UDP/UDP2 tunnel state appears.
                    val transport = decodeTransport(rdpClient.transportInfo())
                    val transportStats = rdpClient.transportStats()
                    _state.update {
                        it.copy(
                            fps = fpsCounter.snapshot(),
                            durationSec = dur,
                            latencyMs = lastLatencyMs,
                            // 操控延迟 = the raw empirical input→decode estimate (METRIC-2: the old
                            // maxOf(cl, lastLatencyMs) clamp to a fresh TCP-handshake RTT was DROPPED —
                            // the handshake includes the server's listen/accept path and over-reported
                            // the floor, while the empirical sample already physically includes the
                            // network leg). The network RTT is now shown on its OWN row, not folded in.
                            controlLatencyMs = rdpClient.controlLatencyMs(),
                            presentLagMs = rdpClient.presentLagMs(),
                            latencyAccepted = rdpClient.latencyAccepted(),
                            latencyDiscarded = rdpClient.latencyDiscarded(),
                            transport = transport,
                            transportStats = transportStats,
                        )
                    }
                } else {
                    // Not connected: clear any lingering metrics once (extracted to a val so the
                    // multi-term check isn't flagged as a complex `if` condition).
                    val hasStaleMetrics = current.fps != 0 || current.durationSec != 0L ||
                        current.latencyMs != -1 || current.controlLatencyMs != -1 ||
                        current.presentLagMs != -1 || current.latencyAccepted != 0 ||
                        current.latencyDiscarded != 0 ||
                        current.transport != RdpTransport.UNKNOWN ||
                        current.transportStats != RdpTransportStats()
                    if (hasStaleMetrics) {
                        _state.update {
                            it.copy(
                                fps = 0,
                                durationSec = 0L,
                                latencyMs = -1,
                                controlLatencyMs = -1,
                                presentLagMs = -1,
                                latencyAccepted = 0,
                                latencyDiscarded = 0,
                                transport = RdpTransport.UNKNOWN,
                                transportStats = RdpTransportStats(),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Background latency probe: every few seconds, measure an approximate RTT to the RDP host and
     * cache it in [lastLatencyMs]. The 1 Hz [startMetricsTicker] is the sole writer of the value
     * into UI state, so this never recomposes the TopAppBar at probe frequency.
     *
     * Caveat (not the cause of any field bug, but worth knowing): this opens a fresh TCP connection
     * to host:port each tick. On a connection-limited tunnel that's a little wasteful — the primary
     * "操控延迟" (input→frame) metric needs no extra connection, so if a tunnel ever proves sensitive,
     * drop this probe rather than the control-latency one.
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
     * Periodically snapshot the live remote framebuffer into this connection's desktop thumbnail
     * (issue: 读取被控电脑桌面图片放到选项中). The connection list card shows the latest snapshot, so
     * a returning user sees what the desktop actually looked like rather than a generic icon. Cheap:
     * [com.hanfengruyue.pocketrdp.core.rdp.BitmapBuffer.snapshot] makes a small downscaled copy and the
     * store JPEG-encodes/writes on its own IO scope. No-op until connected with a published frame.
     */
    private fun startThumbnailCapture() {
        viewModelScope.launch {
            while (isActive) {
                delay(THUMB_CAPTURE_INTERVAL_MS)
                captureThumbnail()
            }
        }
    }

    /**
     * Capture a downscaled snapshot of the current remote frame and persist it as this connection's
     * thumbnail. No-op until connected with a real id and at least one published frame. Safe to call
     * from [disconnect]/[onCleared] because the snapshot is an independent copy and the store encodes
     * it on a scope that outlives this ViewModel.
     */
    private fun captureThumbnail() {
        val st = _state.value
        if (st.connectionId <= 0L || st.status !is SessionConnectionStatus.Connected) return
        val snap = rdpClient.buffer.snapshot(THUMB_MAX_DIM) ?: return
        thumbnailStore.save(st.connectionId, snap)
    }

    /** Decode the native [RdpClient.transportInfo] bitfield. */
    private fun decodeTransport(raw: Int): RdpTransport {
        if (raw < 0) return RdpTransport.UNKNOWN
        val state = raw and TRANSPORT_STATE_MASK
        return when (state) {
            TRANSPORT_STATE_TCP ->
                if (raw and TRANSPORT_FLAG_UDP_FALLBACK != 0) {
                    RdpTransport.TCP_FALLBACK
                } else {
                    RdpTransport.TCP
                }
            TRANSPORT_STATE_UDP_R -> RdpTransport.UDP_R
            TRANSPORT_STATE_UDP_L -> RdpTransport.UDP_L
            TRANSPORT_STATE_UDP2 -> RdpTransport.UDP2
            else -> RdpTransport.UNKNOWN
        }
    }

    /**
     * Scale a requested dynamic-resolution size down so NEITHER edge exceeds the connection's cap
     * (issue: 动态分辨率直接套用手机全分辨率导致被控端渲染压力过大). [dynamicResMaxShortEdge] is the SHORT-edge
     * cap in px (720 / 1080 / 1440); the long edge is bounded to 16:9 of it. 0 = 跟随设备 (no cap) → the
     * size is returned unchanged. Aspect ratio is preserved, the result is NEVER upscaled, and both
     * edges are rounded DOWN to even numbers (RDP servers commonly clamp resolutions to multiples of 2).
     */
    private fun capToDynamicMax(width: Int, height: Int): Pair<Int, Int> {
        val capShort = dynamicResMaxShortEdge
        if (capShort <= 0 || width <= 0 || height <= 0) return width to height
        val capLong = capShort * DYNAMIC_RES_ASPECT_LONG / DYNAMIC_RES_ASPECT_SHORT
        val shortEdge = minOf(width, height)
        val longEdge = maxOf(width, height)
        val scale = minOf(1f, capShort.toFloat() / shortEdge, capLong.toFloat() / longEdge)
        if (scale >= 1f) return width to height
        fun toEven(v: Float): Int = v.roundToInt().let { it - (it and 1) }.coerceAtLeast(2)
        return toEven(width * scale) to toEven(height * scale)
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
            // Cache the auto-resolution setting + the max-edge cap for the session lifetime so the
            // monitor-layout sends below are correctly gated/capped even if the entity changes later.
            dynamicResolutionEnabled = effectiveDynamic
            dynamicResMaxShortEdge = if (effectiveDynamic) entity.dynamicResMax else 0
            val baseW = if (useCustomRes) entity.customWidth else DEFAULT_DYNAMIC_WIDTH
            val baseH = if (useCustomRes) entity.customHeight else DEFAULT_DYNAMIC_HEIGHT
            // Cap the INITIAL size too (capToDynamicMax is a no-op for custom-res, where the cap is 0)
            // so a 720p-capped connection doesn't briefly open at full size before the first
            // monitor-layout PDU corrects it down.
            val (initialW, initialH) = capToDynamicMax(baseW, baseH)
            // Same for clipboard: gates both directions of the text bridge (see clipListener /
            // the ClipboardReceived branch). Off → channel is /clipboard:...direction-to:off too.
            clipboardSyncEnabled = entity.redirectClipboard
            _state.update {
                it.copy(
                    connectionName = entity.name,
                    connectionHost = "${entity.host}:${entity.port}",
                    targetFrameRate = entity.targetFrameRate,
                    // Open in the connection's preferred input mode (1 = 直接触屏 / native touch).
                    mode = if (entity.defaultInputMode == 1) InputMode.TOUCH else InputMode.TRACKPAD,
                    filesRedirectEnabled = entity.redirectFiles,
                    allFilesAccessRequired = false,
                )
            }
            if (entity.redirectFiles && !Environment.isExternalStorageManager()) {
                PocketLogger.w(TAG, "folder redirection requested but MANAGE_EXTERNAL_STORAGE is not granted; waiting before connect")
                _state.update {
                    it.copy(
                        status = SessionConnectionStatus.Idle,
                        filesRedirectEnabled = true,
                        allFilesAccessRequired = true,
                        lastError = null,
                    )
                }
                return@launch
            }
            val plain = runCatching { repository.decryptPassword(entity) }.getOrDefault("")
            // File redirection: export the WHOLE internal storage (/storage/emulated/0) as a "PocketRDP"
            // drive so the remote PC sees ALL phone files (用户需求: 默认共享整个安卓存储). This is a real
            // POSIX path WinPR can open()/stat() (a SAF content:// URI can NOT be used). Reading the whole
            // tree on API 30+ scoped storage needs the MANAGE_EXTERNAL_STORAGE (所有文件访问) permission —
            // prompted contextually in SessionScreen. Until it's granted the path is unreadable and
            // freerdp_path_valid silently SKIPS it (non-fatal, the drive just doesn't appear), so we still
            // pass it and the drive lights up the moment the user grants access.
            val driveDir = if (entity.redirectFiles) {
                runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
            } else {
                null
            }
            val params = RdpConnectionParams(
                connectionId = entity.id,
                host = entity.host,
                port = entity.port,
                username = entity.username,
                domain = entity.domain,
                password = plain,
                colorDepth = entity.colorDepth,
                useH264 = entity.useH264,
                preferAvc420 = entity.preferAvc420,
                useGfx = entity.useGfx,
                dynamicResolution = effectiveDynamic,
                useMultitransport = entity.useMultitransport,
                redirectClipboard = entity.redirectClipboard,
                redirectFiles = entity.redirectFiles,
                sharedFolderUri = entity.sharedFolderUri,
                drivePath = driveDir,
                soundMode = entity.soundMode,
                desktopScaleFactor = entity.desktopScaleFactor,
                initialWidth = initialW,
                initialHeight = initialH,
                acceptedCertThumbprint = entity.certThumbSha256,
                performanceFlags = entity.performanceFlags,
            )
            // Remember everything an auto-reconnect needs, and reset reconnect bookkeeping for this
            // fresh user-initiated connect.
            lastParams = params
            lastConnName = entity.name
            lastHostLabel = "${entity.host}:${entity.port}"
            userInitiatedDisconnect = false
            reconnectAttempt = 0
            reconnectJob?.cancel()
            // Promote the process to a foreground service NOW, while we're still visible (Android
            // 31+ forbids starting an FGS from the background). This is what keeps the connection
            // alive when the user switches to another app — see RdpSessionService.
            startKeepAlive(appContext.getString(R.string.session_notification_status_connecting))
            rdpClient.connect(params)
            repository.touchLastUsed(entity.id)
        }
    }

    /** Start (or refresh) the keep-alive foreground service for the current connection. */
    private fun startKeepAlive(status: String) {
        // Mark "a session is meant to be alive" so a background process-kill is detectable next launch.
        SessionKeepAliveFlag.setActive(appContext, true)
        runCatching { RdpSessionService.start(appContext, lastConnName, lastHostLabel, status) }
            .onFailure { PocketLogger.w(TAG, "startForegroundService failed: ${it.message}") }
    }

    /** Stop the keep-alive service (idempotent). Called on user teardown / give-up. */
    private fun stopKeepAlive() {
        // Clean teardown — clear the flag so we DON'T later mistake this for a background kill.
        SessionKeepAliveFlag.setActive(appContext, false)
        reconnectJob?.cancel()
        RdpSessionService.stop(appContext)
    }

    /**
     * Schedule an auto-reconnect after an UNEXPECTED drop / failed attempt, with exponential
     * backoff. Gives up (and stops the keep-alive service) after [RECONNECT_BACKOFF_MS].size tries.
     * No-op if the teardown was user-initiated or we never had a successful set of params.
     */
    private fun scheduleReconnect() {
        val params = lastParams ?: return
        if (userInitiatedDisconnect) return
        reconnectJob?.cancel()
        if (reconnectAttempt >= RECONNECT_BACKOFF_MS.size) {
            PocketLogger.w(TAG, "auto-reconnect exhausted after $reconnectAttempt tries — giving up")
            stopKeepAlive()
            return
        }
        val delayMs = RECONNECT_BACKOFF_MS[reconnectAttempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
        reconnectAttempt++
        PocketLogger.i(TAG, "auto-reconnect attempt #$reconnectAttempt in ${delayMs}ms")
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (!isActive || userInitiatedDisconnect) return@launch
            // The keep-alive service stays running across the drop (we only stopSelf on a user
            // disconnect), so just re-issue the connect — the service updates its own notification.
            rdpClient.connect(params)
        }
    }

    fun toggleMode() {
        _state.update {
            it.copy(mode = if (it.mode == InputMode.TRACKPAD) InputMode.TOUCH else InputMode.TRACKPAD)
        }
    }

    /**
     * Enter / exit full-screen. This toggles ONLY the app's own top bar (chrome); the Android
     * system bars stay hidden for the whole session (driven in SessionScreen), so this no longer
     * touches them. false = pure picture with just a small exit FAB.
     */
    fun toggleImmersive() {
        _state.update {
            val newVisible = !it.chromeVisible
            // Hiding chrome also dismisses the keyboard/toolbar; restoring chrome leaves IME as-is.
            it.copy(chromeVisible = newVisible, imeVisible = if (newVisible) it.imeVisible else false)
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

    fun typeText(rawText: String) {
        if (rawText.isEmpty()) return
        // Normalise line breaks to a single '\n' first so a CRLF never becomes a DOUBLE Enter in the
        // fallback typing path; the clipboard path re-expands to CRLF for Windows.
        val text = rawText.replace("\r\n", "\n").replace('\r', '\n')
        // A chunk that is MULTI-LINE or LARGE is a PASTE, not live typing. Route it through the remote
        // clipboard + Ctrl+V instead of typing it character-by-character. Char-by-char is wrong for a
        // paste in two ways the user hit in the field:
        //   • it floods the RDP input channel for large text → 断开/闪退 (even paced, tens of thousands
        //     of key PDUs overwhelm the link/server), and
        //   • it turns every newline into an Enter keystroke — '\n' isn't printable ASCII so it goes out
        //     as a unicode key event that Windows treats as Enter (用户报告: 剪贴板里的换行输入进电脑变成回车).
        // A clipboard paste inserts the whole block at ONCE: newlines stay as text and only 4 key events
        // are sent. Needs clipboard redirection (the remote clipboard); when it's off we fall back to the
        // throttled char-by-char typing (still bounded, just can't avoid the newline-as-Enter there).
        val isPaste = text.length > PASTE_VIA_CLIPBOARD_THRESHOLD || text.contains('\n')
        if (isPaste && clipboardSyncEnabled) {
            pasteViaRemoteClipboard(text)
        } else {
            // Offer the chunk to the background type consumer (startTypeConsumer). trySend never blocks;
            // the channel is UNLIMITED and drained by ONE ordered consumer that paces the keystrokes.
            // Per-char routing (ASCII → scancode, else unicode) lives in TextInputEncoder.
            typeChannel.trySend(text)
        }
    }

    /**
     * Paste a block of text into the remote by putting it on the remote clipboard and sending Ctrl+V,
     * instead of typing it out. This is how a large / multi-line paste is delivered (see [typeText]):
     * it preserves newlines as text and sends only 4 key events, so it neither floods the input channel
     * (断开/闪退) nor turns newlines into Enter (换行变回车). The cliprdr push is async, so we wait briefly
     * before the paste so the remote clipboard holds the new text first.
     */
    private fun pasteViaRemoteClipboard(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            // CRLF so Windows apps render the line breaks (a bare \n shows as no break in Notepad etc.),
            // and cap the length the same way the clipboard listener does.
            val crlf = text.replace("\n", "\r\n")
            val payload = if (crlf.length > CLIPBOARD_MAX_CHARS) crlf.take(CLIPBOARD_MAX_CHARS) else crlf
            // Echo guard: mark before sending so the server echoing this text back (ClipboardReceived)
            // — and our own clipboard listener — recognise it and don't bounce it around.
            lastSyncedClipboard = payload
            rdpClient.sendClipboard(payload)
            delay(CLIPBOARD_PASTE_DELAY_MS)
            val ctrl = ScancodeMap.VK.LCONTROL
            val vKey = ScancodeMap.asciiVkFor('v'.code)?.first ?: VK_V_FALLBACK
            rdpClient.sendKeyEvent(ctrl, true)
            rdpClient.sendKeyEvent(vKey, true)
            rdpClient.sendKeyEvent(vKey, false)
            rdpClient.sendKeyEvent(ctrl, false)
        }
    }

    fun onSurfaceResized(width: Int, height: Int) {
        // Auto-resolution disabled → never push a monitor layout; the remote stays at its connect
        // resolution regardless of how the local view is sized (issue: "关闭自动分辨率仍被修改").
        if (!dynamicResolutionEnabled) return
        // Cap the requested size to the connection's dynamic-resolution ceiling FIRST, so we never ask
        // the remote to render at the phone's full (often 1440p+) native resolution (issue: 性能压力过大).
        // Storing the CAPPED size as pending also makes the OnGraphicsResize ack-match (in observeEvents)
        // compare against what we actually sent, so the retry loop still terminates correctly.
        val (capW, capH) = capToDynamicMax(width, height)
        // Always update the pending size synchronously — scheduleMonitorLayoutRetry reads it
        // and we need the *latest* dimensions if a retry happens to fire before the debounce.
        pendingMonitorWidth = capW
        pendingMonitorHeight = capH
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

    /**
     * Start mirroring local clipboard changes to the remote (idempotent; no-op when sync is off).
     * Gated on [localToRemoteClipboardEnabled] — disabled on the current prebuilt .so because a
     * clipboard send before the cliprdr channel is ready tears the session down (see the field).
     */
    private fun registerClipboardListener() {
        if (!localToRemoteClipboardEnabled || !clipboardSyncEnabled || clipListenerRegistered) return
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipListener) }
            .onSuccess { clipListenerRegistered = true }
    }

    /** Stop mirroring local clipboard changes (idempotent). Always called on session teardown. */
    private fun unregisterClipboardListener() {
        clipDebounceJob?.cancel()
        if (!clipListenerRegistered) return
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipListener) }
        clipListenerRegistered = false
    }

    fun disconnect() {
        // Explicit user teardown (disconnect button): stop reconnecting + stop the keep-alive
        // service. rdpClient.disconnect() emits Disconnected(reason="user").
        userInitiatedDisconnect = true
        stopKeepAlive()
        // Grab a final desktop thumbnail BEFORE rdpClient.disconnect() releases the buffer — the
        // snapshot is an independent copy, so the subsequent release is harmless to it.
        captureThumbnail()
        rdpClient.disconnect()
    }

    override fun onCleared() {
        // M7 keep-alive: a *backgrounded* session does NOT reach here — single-Activity Compose nav
        // keeps the ViewModel across onStop, and the RdpSessionService keeps the process alive. This
        // fires only when the user actually LEAVES the session screen (pops the back stack), which
        // IS a deliberate teardown — so disconnect and stop the keep-alive service.
        PocketLogger.i(TAG, "SessionViewModel.onCleared — disconnecting + stopping keep-alive")
        userInitiatedDisconnect = true
        stopKeepAlive()
        // Last chance to refresh the list thumbnail (still Connected here); the store encodes on its
        // own scope, so it survives this ViewModel's scope being cancelled right after onCleared.
        captureThumbnail()
        unregisterClipboardListener()
        typeChannel.close()
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
        // Clipboard local→remote sync: collapse a burst of OnPrimaryClipChanged callbacks into one send
        // at the final stable clip, and cap how much text we push over cliprdr (the 大量文本→断开/闪退 fix).
        private const val CLIPBOARD_DEBOUNCE_MS = 250L
        private const val CLIPBOARD_MAX_CHARS = 256 * 1024
        // typeText: a chunk longer than this (or containing any newline) is treated as a PASTE and is
        // delivered via the remote clipboard + Ctrl+V instead of char-by-char typing — see typeText.
        // High enough that normal typing / IME word commits never trip it; low enough to catch real
        // pastes well before they could flood the input channel.
        private const val PASTE_VIA_CLIPBOARD_THRESHOLD = 200
        // Wait after pushing the clipboard before sending Ctrl+V, so the async cliprdr round-trip has
        // updated the remote clipboard first. (The text was usually already synced when the user copied,
        // so this is mostly insurance.)
        private const val CLIPBOARD_PASTE_DELAY_MS = 400L
        // VK_V (0x56) fallback if the ASCII map ever fails to resolve 'v'.
        private const val VK_V_FALLBACK = 0x56
        // Desktop-thumbnail capture: snapshot the framebuffer this often so the connection card
        // shows a recent picture even if the session ends abruptly (a crash/kill skips the
        // disconnect-time capture). 12 s is frequent enough to stay current without churning the disk.
        private const val THUMB_CAPTURE_INTERVAL_MS = 12_000L
        // Longest edge (px) of the stored thumbnail. Bumped 640 → 1280 to kill the connection-card
        // blur (用户反馈: 主页图片非常模糊): the card spans almost the full screen width, so a 640px source
        // was upscaled ~2× and looked soft. 1280px (paired with JPEG quality 92, see ConnectionThumbnail
        // Store) shows ~1:1 on phones and stays ~100–200 KB. snapshot() never upscales beyond the
        // framebuffer, so a sub-1280 remote desktop just stores at its own size.
        private const val THUMB_MAX_DIM = 1280
        private const val TRANSPORT_STATE_MASK = 0x0F
        private const val TRANSPORT_STATE_TCP = 0x01
        private const val TRANSPORT_STATE_UDP_R = 0x02
        private const val TRANSPORT_STATE_UDP_L = 0x03
        private const val TRANSPORT_STATE_UDP2 = 0x04
        private const val TRANSPORT_FLAG_UDP_FALLBACK = 0x100
        // Dynamic-resolution default initial size (issue: 1920×1080 fallback) and the 16:9 bounding box
        // used by capToDynamicMax to derive the long-edge cap from the chosen short-edge cap.
        private const val DEFAULT_DYNAMIC_WIDTH = 1920
        private const val DEFAULT_DYNAMIC_HEIGHT = 1080
        private const val DYNAMIC_RES_ASPECT_LONG = 16
        private const val DYNAMIC_RES_ASPECT_SHORT = 9
        // Auto-reconnect backoff after an unexpected drop / failed attempt (ms). The array length
        // also caps the number of tries — after the last one we give up and stop the keep-alive
        // service. Resets to attempt 0 on every successful Connected.
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
