package com.hanfengruyue.pocketrdp.feature.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.TextInputEncoder
import com.hanfengruyue.pocketrdp.feature.session.service.RdpSessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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
    /** Negotiated network transport (TCP / RDP-UDP multitransport) for the status badge (issue #2). */
    val transport: RdpTransport = RdpTransport.UNKNOWN,
    val targetFrameRate: Int = 0,
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
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!clipboardSyncEnabled) return@OnPrimaryClipChangedListener
        val txt = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(appContext)
            ?.toString()
            .orEmpty()
        // Skip empties and anything we just received from the remote (loop guard).
        if (txt.isEmpty() || txt == lastSyncedClipboard) return@OnPrimaryClipChangedListener
        lastSyncedClipboard = txt
        rdpClient.sendClipboard(txt)
    }

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
        if (id > 0L) launchConnect(id)
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
                        if (!wasReconnect) startKeepAlive("已连接")
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
                        it.copy(
                            fps = fpsCounter.snapshot(),
                            durationSec = dur,
                            latencyMs = lastLatencyMs,
                            // Felt control latency can never be below the raw network RTT — clamp up so
                            // the low-percentile estimate can't read under that physical floor.
                            controlLatencyMs = rdpClient.controlLatencyMs().let { cl ->
                                if (cl >= 0 && lastLatencyMs >= 0) maxOf(cl, lastLatencyMs) else cl
                            },
                            transport = transport,
                        )
                    }
                } else {
                    // Not connected: clear any lingering metrics once (extracted to a val so the
                    // multi-term check isn't flagged as a complex `if` condition).
                    val hasStaleMetrics = current.fps != 0 || current.durationSec != 0L ||
                        current.latencyMs != -1 || current.controlLatencyMs != -1 ||
                        current.transport != RdpTransport.UNKNOWN
                    if (hasStaleMetrics) {
                        _state.update {
                            it.copy(
                                fps = 0,
                                durationSec = 0L,
                                latencyMs = -1,
                                controlLatencyMs = -1,
                                transport = RdpTransport.UNKNOWN,
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
     * Invoked by RdpSurface once per actually-rendered frame (always on the UI thread). This —
     * not the content-update event — drives the FPS counter, so the value the 1 Hz ticker
     * snapshots is the steady render rate (target frame rate clamped to the screen refresh
     * rate), not the bursty/zero-when-idle content rate.
     */
    fun onFrameRendered() = fpsCounter.tick()

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
                )
            }
            val plain = runCatching { repository.decryptPassword(entity) }.getOrDefault("")
            // File redirection: export an app-private POSIX folder (a real filesystem path — NOT the
            // SAF content:// URI, which WinPR can't open()) as a "PocketRDP" drive. mkdirs() up front
            // because FreeRDP silently skips a non-existent drive path at parse time.
            val driveDir = if (entity.redirectFiles) {
                runCatching {
                    appContext.getExternalFilesDir(null)?.let { base ->
                        File(base, "PocketRDP").apply { mkdirs() }.absolutePath
                    }
                }.getOrNull()
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
            startKeepAlive("正在连接…")
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
        // Desktop-thumbnail capture: snapshot the framebuffer this often so the connection card
        // shows a recent picture even if the session ends abruptly (a crash/kill skips the
        // disconnect-time capture). 12 s is frequent enough to stay current without churning the disk.
        private const val THUMB_CAPTURE_INTERVAL_MS = 12_000L
        // Longest edge (px) of the stored thumbnail. ~640 keeps a 16:9 card crisp on hi-dpi phones
        // while the JPEG stays a few tens of KB.
        private const val THUMB_MAX_DIM = 640
        // Auto-reconnect backoff after an unexpected drop / failed attempt (ms). The array length
        // also caps the number of tries — after the last one we give up and stop the keep-alive
        // service. Resets to attempt 0 on every successful Connected.
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
