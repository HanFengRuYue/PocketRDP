package com.hanfengruyue.pocketrdp.feature.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferencesRepository
import com.hanfengruyue.pocketrdp.core.data.preferences.DEFAULT_AUTO_RECONNECT
import com.hanfengruyue.pocketrdp.core.data.preferences.DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS
import com.hanfengruyue.pocketrdp.core.data.preferences.LANGUAGE_SYSTEM
import com.hanfengruyue.pocketrdp.core.data.preferences.localizedContextForLanguageTag
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionOperationCoordinator
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionRepository
import com.hanfengruyue.pocketrdp.core.data.thumbnail.ConnectionThumbnailStore
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpClient
import com.hanfengruyue.pocketrdp.core.rdp.RdpConnectionParams
import com.hanfengruyue.pocketrdp.core.rdp.RdpDriveRedirectionPlan
import com.hanfengruyue.pocketrdp.core.rdp.RdpEvent
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportPhase
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportSnapshot
import com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag
import com.hanfengruyue.pocketrdp.core.rdp.planRdpDriveRedirection
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.TextInputEncoder
import com.hanfengruyue.pocketrdp.feature.session.service.RdpSessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.absoluteValue

sealed interface SessionConnectionStatus {
    data object Idle : SessionConnectionStatus
    data object Connecting : SessionConnectionStatus
    data object Reconnecting : SessionConnectionStatus
    data object Connected : SessionConnectionStatus
    data class Disconnected(val reason: String?) : SessionConnectionStatus
    data class Failed(val error: String) : SessionConnectionStatus
}

data class SessionMetricsUiState(
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
    val transportSnapshot: RdpTransportSnapshot = RdpTransportSnapshot(),
)

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
    val targetFrameRate: Int = 0,
    /**
     * Whether this connection has folder redirection on (整盘共享). When true, SessionScreen prompts
     * for the MANAGE_EXTERNAL_STORAGE (所有文件访问) permission if it isn't granted yet — without it the
     * remote "PocketRDP" drive shows empty. Loaded from entity.redirectFiles in launchConnect.
     */
    val filesRedirectEnabled: Boolean = false,
    val allFilesAccessRequired: Boolean = false,
    val functionToolbarQuickIds: List<String> = DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS,
    val pendingCertificate: PendingCertificate? = null,
    val pendingCredentialsPrompt: CredentialsPromptState? = null,
    val lastError: String? = null,
)

data class PendingCertificate(
    val host: String,
    val port: Int,
    val sha256: String,
    val isChange: Boolean,
)

data class CredentialsPromptState(
    val host: String,
    val initialUsername: String,
    val initialDomain: String,
)

private data class SessionConnectConfig(
    val effectiveDynamic: Boolean,
    val effectiveRedirectFiles: Boolean,
    val initialWidth: Int,
    val initialHeight: Int,
)

private sealed interface TextInputCommand {
    val generation: Long

    data class Type(val text: String, override val generation: Long) : TextInputCommand
    data class Paste(val text: String, override val generation: Long) : TextInputCommand
}

internal fun truncateClipboardText(text: String, maxChars: Int): String {
    require(maxChars >= 0) { "maxChars must be non-negative" }
    if (text.length <= maxChars) return text
    if (maxChars == 0) return ""
    val end = if (text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    return text.substring(0, end)
}

internal fun toWindowsClipboardText(text: String): String =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\n", "\r\n")

private val toolbarModifierOrder = listOf(
    ScancodeMap.Modifier.CTRL,
    ScancodeMap.Modifier.ALT,
    ScancodeMap.Modifier.SHIFT,
    ScancodeMap.Modifier.WIN,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    val rdpClient: RdpClient,
    private val repository: ConnectionRepository,
    private val operationCoordinator: ConnectionOperationCoordinator,
    private val preferencesRepository: AppPreferencesRepository,
    private val thumbnailStore: ConnectionThumbnailStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _metricsState = MutableStateFlow(SessionMetricsUiState())
    val metricsState: StateFlow<SessionMetricsUiState> = _metricsState.asStateFlow()

    private val statusMenuExpanded = MutableStateFlow(false)
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
    // De-dupe token shared by BOTH directions to break the echo loop: remote→local sets it before
    // setPrimaryClip (so our own OnPrimaryClipChanged sees an unchanged value and skips), and
    // local→remote sets it before sendClipboard (so the server echoing the same text back as
    // ClipboardReceived is ignored). Without it the two directions ping-pong the same text forever.
    @Volatile private var lastRemoteClipboardPayload: String = ""
    @Volatile private var lastAppliedRemoteClipboard: String = ""
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
            if (raw.isEmpty()) return@launch
            if (raw == lastAppliedRemoteClipboard) {
                lastAppliedRemoteClipboard = ""
                return@launch
            }
            val txt = if (raw.length > CLIPBOARD_MAX_CHARS) {
                PocketLogger.w(TAG, "clipboard ${raw.length} chars > cap $CLIPBOARD_MAX_CHARS, truncating")
                truncateClipboardText(raw, CLIPBOARD_MAX_CHARS)
            } else {
                raw
            }
            lastRemoteClipboardPayload = txt
            rdpClient.sendClipboard(txt)
        }
    }

    // Large-text input queue (the 粘贴大量文本→断开/闪退 fix). typeText offers each committed chunk here;
    // a SINGLE background consumer coroutine (startTypeConsumer) types it via TextInputEncoder.typeThrottled
    // so keystrokes are paced (no UI block, no input-channel flood) AND ordered across overlapping pastes.
    private val typeChannel = Channel<TextInputCommand>(
        capacity = TYPE_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var typeConsumerJob: Job? = null
    @Volatile private var inputGeneration: Long = 0L

    // Latest latency probe result (ms, -1 = unknown), sampled by a background coroutine and folded
    // into UI state by the 1 Hz metrics ticker (the sole writer of metrics — see CLAUDE.md).
    @Volatile private var lastLatencyMs: Int = -1

    // The last view size onSurfaceResized was called with. Used to re-emit a monitor layout
    // PDU once the RDP control channel finishes negotiating — without that the very first
    // layout we send (right after Compose measures the view) is silently dropped because the
    // dynvc/disp channel isn't open yet.
    private var lastSurfaceWidth: Int = 0
    private var lastSurfaceHeight: Int = 0
    private var pendingMonitorWidth: Int = 0
    private var pendingMonitorHeight: Int = 0
    private var pendingMonitorResizeDeferred: Boolean = false
    private var monitorLayoutRetryJob: Job? = null
    // Debounce job for onSurfaceResized. Soft-keyboard show/hide animates the AndroidView's
    // height pixel-by-pixel (observed: 20+ resize callbacks over ~150 ms), and pushing a
    // DISPLAY_CONTROL_MONITOR_LAYOUT PDU on each one floods the disp channel — RDP servers
    // (or VPN middleboxes) react by closing the connection a few seconds later. The debounce
    // collapses a burst into a single PDU at the *final* stable size.
    private var monitorLayoutDebounceJob: Job? = null

    // --- Background keep-alive + auto-reconnect (M7) ---
    // The keep-alive RdpSessionService keeps the PROCESS alive while backgrounded. Each
    // SessionViewModel owns its own RdpClient; the service observes the shared RdpSessionRegistry so
    // one session ending does not stop keep-alive for other active sessions.
    //
    // Reconnect distinguishes intent via the Disconnected event's reason: RdpClient.disconnect()
    // emits reason="user" (deliberate — don't reconnect), while a native/network drop emits
    // reason=null (unexpected — reconnect with backoff). A failed connect attempt (RdpEvent.Failed)
    // is also retried unless the teardown was user-initiated.
    private var lastParams: RdpConnectionParams? = null
    private var userInitiatedDisconnect: Boolean = false
    private var reconnectAttempt: Int = 0
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var certificateDecisionPending: Boolean = false
    private var udpDowngradedForSession: Boolean = false
    private var filesRedirectSkippedForSession: Boolean = false
    @Volatile private var autoReconnectEnabled: Boolean = DEFAULT_AUTO_RECONNECT
    @Volatile private var currentLanguageTag: String = LANGUAGE_SYSTEM

    init {
        observeEvents()
        observeFrameUpdates()
        observeClipboardUpdates()
        observeResizeUpdates()
        observePreferences()
        startMetricsTicker()
        startLatencyProbe()
        startThumbnailCapture()
    }

    fun onStatusMenuExpandedChange(expanded: Boolean) {
        if (statusMenuExpanded.value == expanded) return
        statusMenuExpanded.value = expanded
        if (expanded) {
            refreshMetricsSnapshot()
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            var lastObservedAutoReconnect: Boolean? = null
            preferencesRepository.preferences.collect { prefs ->
                currentLanguageTag = prefs.languageTag
                autoReconnectEnabled = prefs.autoReconnect
                if (lastObservedAutoReconnect != prefs.autoReconnect) {
                    lastObservedAutoReconnect = prefs.autoReconnect
                    if (!prefs.autoReconnect) {
                        rdpClient.setAutoReconnectEnabled(false)
                        cancelPendingAutoReconnect()
                    }
                }
                _state.update { it.copy(functionToolbarQuickIds = prefs.functionToolbarQuickIds) }
            }
        }
    }

    private fun localizedString(@StringRes resId: Int): String =
        appContext.localizedContextForLanguageTag(currentLanguageTag).getString(resId)

    fun ensureStarted(connectionId: Long) {
        startIfAllowed(connectionId, allowStoppedSessionRestart = true)
    }

    /**
     * Lifecycle resumes are not an explicit retry gesture. In particular, restarting a failed
     * authentication attempt merely because the user backgrounded and resumed the app can consume
     * another server-side login attempt and contribute to an account lockout. Certificate rejection
     * must likewise remain final until the user deliberately leaves and re-opens the session.
     */
    fun ensureStartedOnResume(connectionId: Long) {
        startIfAllowed(connectionId, allowStoppedSessionRestart = false)
    }

    private fun startIfAllowed(connectionId: Long, allowStoppedSessionRestart: Boolean) {
        if (connectionId <= 0L) return
        val current = _state.value
        if (!shouldLaunchConnect(current, connectionId, allowStoppedSessionRestart)) return
        if (current.connectionId != connectionId) {
            udpDowngradedForSession = false
            filesRedirectSkippedForSession = false
        }
        _state.update {
            it.copy(
                connectionId = connectionId,
                // Claim the launch synchronously. Permission-result and ON_RESUME callbacks can
                // arrive back-to-back; waiting for RdpClient.Connecting would let both start a
                // separate repository/decrypt/native-connect coroutine for the same session.
                status = SessionConnectionStatus.Connecting,
                pendingCertificate = null,
                pendingCredentialsPrompt = null,
            )
        }
        // A foreground/manual restart supersedes any delayed auto-reconnect left by the previous
        // failure. Cancel it before launching repository I/O so the timer cannot win that race.
        reconnectJob?.cancel()
        launchConnect(connectionId)
    }

    /**
     * Explicit in-session manual reconnect from the Failed / Disconnected overlay or toolbar.
     * Preserves [udpDowngradedForSession] so a session that already fell back from UDP to TCP
     * stays TCP-only until the user leaves and manually starts a fresh session.
     */
    fun reconnect() {
        val current = _state.value
        val connectionId = current.connectionId
        val canManualReconnect = current.status is SessionConnectionStatus.Failed ||
            current.status is SessionConnectionStatus.Disconnected ||
            current.status is SessionConnectionStatus.Idle
        if (connectionId <= 0L || !canManualReconnect) return
        userInitiatedDisconnect = false
        certificateDecisionPending = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        _state.update {
            it.copy(
                status = SessionConnectionStatus.Connecting,
                pendingCertificate = null,
                pendingCredentialsPrompt = null,
                allFilesAccessRequired = false,
                lastError = null,
            )
        }
        launchConnect(connectionId)
    }

    /**
     * Retry the pending connection after returning from the all-files-access settings page.
     *
     * @return true when access is still missing and the UI should show the contextual prompt again.
     */
    fun retryAfterAllFilesAccess(): Boolean {
        val current = _state.value
        if (!current.allFilesAccessRequired) return false
        if (!Environment.isExternalStorageManager()) return true
        // ON_RESUME invokes this immediately before ensureStarted(). Claim the launch
        // synchronously so that second path observes Connecting and cannot create a duplicate
        // repository/decrypt/native-connect pipeline for the same session.
        _state.update {
            it.copy(
                status = SessionConnectionStatus.Connecting,
                allFilesAccessRequired = false,
                pendingCertificate = null,
                pendingCredentialsPrompt = null,
                lastError = null,
            )
        }
        reconnectJob?.cancel()
        launchConnect(current.connectionId)
        return false
    }

    /**
     * User tapped "Not now" on the All-Files-Access dialog: continue connecting without folder
     * redirection for this session rather than stranding the user in [SessionConnectionStatus.Idle].
     */
    fun dismissAllFilesAccessAndContinueWithoutDrive() {
        val current = _state.value
        if (!current.allFilesAccessRequired || current.connectionId <= 0L) return
        filesRedirectSkippedForSession = true
        _state.update {
            it.copy(
                status = SessionConnectionStatus.Connecting,
                filesRedirectEnabled = false,
                allFilesAccessRequired = false,
                pendingCertificate = null,
                pendingCredentialsPrompt = null,
                lastError = null,
            )
        }
        reconnectJob?.cancel()
        launchConnect(current.connectionId)
    }

    fun submitCredentials(username: String, domain: String, password: String) {
        if (_state.value.pendingCredentialsPrompt == null) return
        _state.update { it.copy(pendingCredentialsPrompt = null, lastError = null) }
        lastParams = lastParams?.copy(username = username, domain = domain, password = password)
        rdpClient.submitCredentials(username, domain, password)
    }

    fun cancelCredentials() {
        if (_state.value.pendingCredentialsPrompt == null) return
        userInitiatedDisconnect = true
        lastParams = null
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        _state.update { it.copy(pendingCredentialsPrompt = null) }
        rdpClient.cancelCredentials()
    }

    /**
     * Single ordered consumer of [typeChannel]: types each committed text chunk on a background
     * thread, paced by [TextInputEncoder.typeThrottled]. One consumer (not one coroutine per chunk)
     * keeps keystroke order correct when pastes overlap. Re-reads unicode support per chunk (a cheap
     * native getter) so it tracks the post-connect capability flip.
     */
    private fun startTypeConsumer() {
        typeConsumerJob?.cancel()
        val generation = ++inputGeneration
        typeConsumerJob = viewModelScope.launch(Dispatchers.Default) {
            for (command in typeChannel) {
                if (command.generation != generation ||
                    generation != inputGeneration ||
                    !canQueueTextInput(_state.value.status)
                ) {
                    continue
                }
                runCatching {
                    when (command) {
                        is TextInputCommand.Type -> TextInputEncoder.typeThrottled(
                            text = command.text,
                            unicodeSupported = rdpClient.isUnicodeInputSupported(),
                            sendKey = { key, down ->
                                if (generation == inputGeneration &&
                                    !isProtectedStickyModifierKey(key)
                                ) {
                                    rdpClient.sendKeyEvent(key, down)
                                }
                            },
                            sendUnicode = { codePoint, down ->
                                if (generation == inputGeneration) {
                                    rdpClient.sendUnicodeKey(codePoint, down)
                                }
                            },
                        )
                        is TextInputCommand.Paste -> pasteViaRemoteClipboard(command.text, generation)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    PocketLogger.w(TAG, "text input command failed: ${error.message}")
                }
            }
        }
    }

    private fun latchUdpDowngradeIfNeeded(observed: Boolean) {
        if (observed && !udpDowngradedForSession) {
            udpDowngradedForSession = true
            lastParams = lastParams?.copy(useMultitransport = false)
            PocketLogger.w(TAG, "UDP tunnel failed; all app-level retries for this session are TCP-only")
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            rdpClient.events.collect { event ->
                when (event) {
                    is RdpEvent.Connecting -> _state.update {
                        val nextStatus = if (reconnectAttempt > 0) {
                            SessionConnectionStatus.Reconnecting
                        } else {
                            SessionConnectionStatus.Connecting
                        }
                        it.copy(status = nextStatus)
                    }
                    is RdpEvent.Connected -> {
                        certificateDecisionPending = false
                        // A live connection: clear reconnect bookkeeping so a later drop starts its
                        // backoff from zero. Keep-alive FGS was already promoted while foregrounded
                        // in launchConnect(), and RdpSessionService observes RdpSessionRegistry.
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Connected,
                                pendingCertificate = null,
                                pendingCredentialsPrompt = null,
                                lastError = null,
                            )
                        }
                        if (statusMenuExpanded.value) {
                            refreshMetricsSnapshot()
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
                        startTypeConsumer()
                    }
                    is RdpEvent.Disconnected -> {
                        val wasConnected = _state.value.status is SessionConnectionStatus.Connected
                        if (wasConnected && rdpClient.buffer.hasPublishedFrame.value) {
                            captureThumbnail()
                        }
                        latchUdpDowngradeIfNeeded(event.udpDowngraded || rdpClient.hasObservedUdpDowngrade())
                        monitorLayoutRetryJob?.cancel()
                        monitorLayoutDebounceJob?.cancel()
                        unregisterClipboardListener()
                        stopTypeConsumer()
                        clearStickyModifiers(sendReleases = false)
                        fpsCounter.reset()
                        lastLatencyMs = -1
                        _metricsState.value = SessionMetricsUiState()
                        _state.update {
                            it.copy(
                                status = statusAfterDisconnect(it.status, event.reason),
                                imeVisible = false,
                                pendingCredentialsPrompt = null,
                            )
                        }
                        when {
                            event.reason == "user" -> {
                                userInitiatedDisconnect = true
                                lastParams = null
                                reconnectJob?.cancel()
                                stopKeepAlive()
                            }
                            certificateDecisionPending -> {
                                reconnectJob?.cancel()
                            }
                            shouldScheduleAutoReconnect(
                                autoReconnectEnabled = autoReconnectEnabled,
                                eventRetryable = event.retryable,
                                hasReconnectParams = lastParams != null,
                                userInitiatedDisconnect = userInitiatedDisconnect,
                                certificateDecisionPending = certificateDecisionPending,
                            ) -> {
                                scheduleReconnect()
                            }
                            else -> {
                                lastParams = null
                                reconnectJob?.cancel()
                                rdpClient.clearReconnectingSession()
                                stopKeepAlive()
                            }
                        }
                    }
                    is RdpEvent.Failed -> {
                        latchUdpDowngradeIfNeeded(event.udpDowngraded || rdpClient.hasObservedUdpDowngrade())
                        monitorLayoutRetryJob?.cancel()
                        monitorLayoutDebounceJob?.cancel()
                        unregisterClipboardListener()
                        stopTypeConsumer()
                        clearStickyModifiers(sendReleases = false)
                        fpsCounter.reset()
                        lastLatencyMs = -1
                        _metricsState.value = SessionMetricsUiState()
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Failed(event.error),
                                imeVisible = false,
                                pendingCredentialsPrompt = null,
                                lastError = event.error,
                            )
                        }
                        // A failed attempt (incl. mid-session reconnect) is retried with backoff
                        // unless auto-reconnect is off, the user tore the session down, or the
                        // failure is permanent.
                        val canAutoReconnect = shouldScheduleAutoReconnect(
                            autoReconnectEnabled = autoReconnectEnabled,
                            eventRetryable = event.retryable,
                            hasReconnectParams = lastParams != null,
                            userInitiatedDisconnect = userInitiatedDisconnect,
                            certificateDecisionPending = certificateDecisionPending,
                        )
                        if (canAutoReconnect) {
                            scheduleReconnect()
                        } else {
                            reconnectJob?.cancel()
                            if (!certificateDecisionPending) {
                                // Auto-reconnect is disabled or impossible for a permanent failure,
                                // so do not retain decrypted credentials or keep-alive locks.
                                lastParams = null
                                rdpClient.clearReconnectingSession()
                                stopKeepAlive()
                            }
                        }
                    }
                    is RdpEvent.CertificatePrompt -> {
                        certificateDecisionPending = true
                        // Accept/reject reloads the connection from the repository; the pending
                        // trust decision does not need to keep a decrypted password in memory.
                        lastParams = null
                        reconnectJob?.cancel()
                        _state.update {
                            it.copy(
                                pendingCertificate = PendingCertificate(
                                    host = event.host,
                                    port = event.port,
                                    sha256 = event.sha256,
                                    isChange = event.isChange,
                                ),
                                pendingCredentialsPrompt = null,
                            )
                        }
                    }
                    is RdpEvent.CredentialsRequired -> {
                        reconnectJob?.cancel()
                        _state.update {
                            it.copy(
                                pendingCredentialsPrompt = CredentialsPromptState(
                                    host = event.host,
                                    initialUsername = event.initialUsername,
                                    initialDomain = event.initialDomain,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeFrameUpdates() {
        viewModelScope.launch(Dispatchers.Default) {
            rdpClient.frameUpdates.collect {
                fpsCounter.tick()
            }
        }
    }

    private fun observeClipboardUpdates() {
        viewModelScope.launch {
            rdpClient.clipboardUpdates.collect { text ->
                applyRemoteClipboard(text)
            }
        }
    }

    private fun observeResizeUpdates() {
        viewModelScope.launch {
            rdpClient.resizeUpdates.collect { event ->
                _state.update { it.copy(remoteWidth = event.width, remoteHeight = event.height) }
                // The server occasionally clamps to multiples of 2 (we've seen 1278 come back
                // from a 1279 request, 3038 from 3039), so tolerate small differences when
                // deciding that the monitor-layout request was acknowledged.
                val acknowledged = isMonitorLayoutMatchingRemote(
                    targetWidth = pendingMonitorWidth,
                    targetHeight = pendingMonitorHeight,
                    remoteWidth = event.width,
                    remoteHeight = event.height,
                )
                if (acknowledged) {
                    PocketLogger.i(
                        TAG,
                        "monitor layout acknowledged (remote=${event.width}x${event.height})",
                    )
                    pendingMonitorResizeDeferred = false
                    monitorLayoutRetryJob?.cancel()
                }
            }
        }
    }

    private fun applyRemoteClipboard(text: String) {
        val remoteText = truncateClipboardText(text, CLIPBOARD_MAX_CHARS)
        if (text.length > CLIPBOARD_MAX_CHARS) {
            PocketLogger.w(TAG, "remote clipboard exceeded the safe size cap; truncating")
        }
        if (remoteText == lastRemoteClipboardPayload) {
            lastRemoteClipboardPayload = ""
        } else if (clipboardSyncEnabled && remoteText.isNotEmpty() &&
            remoteText != lastAppliedRemoteClipboard
        ) {
            lastAppliedRemoteClipboard = remoteText
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText("PocketRDP", remoteText),
            )
        }
    }

    /**
     * 1 Hz ticker: always polls [RdpClient.transportSnapshot] while connected so UDP-to-TCP
     * downgrade latching remains prompt, and publishes UI telemetry into [metricsState] only when
     * the status popup is open/observed so the main session screen never recomposes at 1 Hz.
     */
    private fun startMetricsTicker() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                if (_state.value.status is SessionConnectionStatus.Connected) {
                    val transportSnapshot = rdpClient.transportSnapshot()
                    latchUdpDowngradeIfNeeded(isUdpDowngradeObserved(transportSnapshot))
                    if (statusMenuExpanded.value || _metricsState.subscriptionCount.value > 0) {
                        publishMetricsSnapshot(transportSnapshot)
                    }
                } else if (_metricsState.value != SessionMetricsUiState()) {
                    _metricsState.value = SessionMetricsUiState()
                }
            }
        }
    }

    private fun refreshMetricsSnapshot() {
        if (_state.value.status !is SessionConnectionStatus.Connected) return
        val transportSnapshot = rdpClient.transportSnapshot()
        latchUdpDowngradeIfNeeded(isUdpDowngradeObserved(transportSnapshot))
        publishMetricsSnapshot(transportSnapshot)
    }

    private fun publishMetricsSnapshot(transportSnapshot: RdpTransportSnapshot) {
        _metricsState.value = SessionMetricsUiState(
            fps = fpsCounter.snapshot(),
            latencyMs = lastLatencyMs,
            controlLatencyMs = rdpClient.controlLatencyMs(),
            presentLagMs = rdpClient.presentLagMs(),
            latencyAccepted = rdpClient.latencyAccepted(),
            latencyDiscarded = rdpClient.latencyDiscarded(),
            transportSnapshot = transportSnapshot,
        )
    }

    /**
     * On-demand network RTT probe: opens a TCP handshake to host:port ONLY while the status
     * dropdown is expanded so collapsed sessions never waste sockets or wake the radio.
     */
    private fun startLatencyProbe() {
        viewModelScope.launch {
            statusMenuExpanded.collectLatest { expanded ->
                if (!expanded) return@collectLatest
                while (isActive) {
                    val measured = if (_state.value.status is SessionConnectionStatus.Connected) {
                        rdpClient.measureLatencyMs()
                    } else {
                        -1
                    }
                    lastLatencyMs = measured
                    if (_state.value.status is SessionConnectionStatus.Connected) {
                        _metricsState.update { it.copy(latencyMs = measured) }
                    }
                    delay(LATENCY_PROBE_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Periodically snapshot the live remote framebuffer off the main thread into this connection's
     * desktop thumbnail. No-op until connected with a published frame.
     */
    private fun startThumbnailCapture() {
        viewModelScope.launch {
            while (isActive) {
                delay(THUMB_CAPTURE_INTERVAL_MS)
                captureThumbnailAsync()
            }
        }
    }

    private suspend fun captureThumbnailAsync() {
        val st = _state.value
        if (st.connectionId <= 0L || st.status !is SessionConnectionStatus.Connected) return
        val snap = withContext(Dispatchers.Default) {
            rdpClient.buffer.snapshot(THUMB_MAX_DIM)
        } ?: return
        thumbnailStore.save(st.connectionId, snap)
    }

    /**
     * Synchronous snapshot used right before [rdpClient.disconnect] releases the framebuffer so
     * teardown captures the final frame prior to [BitmapBuffer.release].
     */
    private fun captureThumbnail() {
        val st = _state.value
        if (st.connectionId <= 0L || st.status !is SessionConnectionStatus.Connected) return
        val snap = rdpClient.buffer.snapshot(THUMB_MAX_DIM) ?: return
        thumbnailStore.save(st.connectionId, snap)
    }


    /**
     * Cap the requested dynamic-resolution size. [dynamicResMaxShortEdge] is the SHORT-edge cap in px
     * selected by the connection; 0 = 跟随设备 (no cap). Aspect ratio is preserved, the result is NEVER
     * upscaled, and both edges are rounded DOWN to even numbers.
     */
    private fun capToDynamicMax(width: Int, height: Int): Pair<Int, Int> {
        return capDynamicResolutionToMax(width, height, dynamicResMaxShortEdge)
    }

    private fun reapplyDynamicResolutionCap() {
        if (!dynamicResolutionEnabled) {
            monitorLayoutDebounceJob?.cancel()
            monitorLayoutRetryJob?.cancel()
            pendingMonitorWidth = 0
            pendingMonitorHeight = 0
            pendingMonitorResizeDeferred = false
            return
        }
        if (lastSurfaceWidth > 0 && lastSurfaceHeight > 0) {
            val (cappedW, cappedH) = capToDynamicMax(lastSurfaceWidth, lastSurfaceHeight)
            pendingMonitorWidth = cappedW
            pendingMonitorHeight = cappedH
        }
    }

    private suspend fun resolveDriveRedirectionPlan(redirectFiles: Boolean): RdpDriveRedirectionPlan =
        withContext(Dispatchers.IO) {
            planRdpDriveRedirection(
                redirectFiles = redirectFiles,
                allFilesAccessGranted = Environment.isExternalStorageManager(),
                storageMounted = runCatching { Environment.getExternalStorageState() }
                    .map { it == Environment.MEDIA_MOUNTED }
                    .getOrDefault(false),
                externalStoragePath = runCatching {
                    Environment.getExternalStorageDirectory()?.absolutePath
                }.getOrNull(),
                pathExists = { path -> File(path).exists() },
                pathIsDirectory = { path -> File(path).isDirectory },
                pathCanRead = { path -> File(path).canRead() },
                pathCanWrite = { path -> File(path).canWrite() },
            )
        }

    private inline fun <T> runCatchingNonCancellable(block: () -> T): Result<T> =
        runCatching(block).onFailure { error ->
            if (error is CancellationException) throw error
        }

    private suspend fun loadConnectionEntityOrFail(id: Long): ConnectionEntity? {
        val entity = runCatchingNonCancellable { repository.findById(id) }.getOrElse { error ->
            PocketLogger.e(TAG, "connection configuration could not be loaded", error)
            failWithLocalizedMessage(R.string.session_connection_load_failed)
            return null
        }
        if (entity == null) {
            PocketLogger.e(TAG, "connection id=$id not found in repository")
            failWithLocalizedMessage(R.string.session_connection_load_failed)
        }
        return entity
    }

    private suspend fun decryptPasswordOrFail(entity: ConnectionEntity): String? =
        runCatchingNonCancellable {
            withContext(Dispatchers.IO) { repository.decryptPassword(entity) }
        }.getOrElse { error ->
            PocketLogger.e(TAG, "saved credential could not be decrypted", error)
            lastParams = null
            failWithLocalizedMessage(R.string.session_credential_unavailable)
            null
        }

    private fun failWithLocalizedMessage(@StringRes resId: Int) {
        val message = localizedString(resId)
        _state.update {
            it.copy(
                status = SessionConnectionStatus.Failed(message),
                lastError = message,
            )
        }
    }

    private fun configureSessionFromEntity(entity: ConnectionEntity): SessionConnectConfig {
        val useCustomRes = entity.customWidth > 0 && entity.customHeight > 0
        val effectiveDynamic = entity.dynamicResolution && !useCustomRes
        dynamicResolutionEnabled = effectiveDynamic
        dynamicResMaxShortEdge = if (effectiveDynamic) entity.dynamicResMax else 0
        reapplyDynamicResolutionCap()
        val baseW = if (useCustomRes) entity.customWidth else DEFAULT_DYNAMIC_WIDTH
        val baseH = if (useCustomRes) entity.customHeight else DEFAULT_DYNAMIC_HEIGHT
        val (initialW, initialH) = capToDynamicMax(baseW, baseH)
        clipboardSyncEnabled = entity.redirectClipboard
        val effectiveRedirectFiles = entity.redirectFiles && !filesRedirectSkippedForSession
        _state.update {
            it.copy(
                connectionName = entity.name,
                connectionHost = "${entity.host}:${entity.port}",
                targetFrameRate = entity.targetFrameRate,
                mode = if (entity.defaultInputMode == 1) InputMode.TOUCH else InputMode.TRACKPAD,
                filesRedirectEnabled = effectiveRedirectFiles,
                allFilesAccessRequired = false,
            )
        }
        return SessionConnectConfig(
            effectiveDynamic = effectiveDynamic,
            effectiveRedirectFiles = effectiveRedirectFiles,
            initialWidth = initialW,
            initialHeight = initialH,
        )
    }

    private fun launchConnect(id: Long) {
        // This path always reloads the authoritative row and constructs a fresh parameter set.
        // Release any password retained for an earlier connection before permission/storage I/O:
        // a paused or failed preflight must not keep stale credentials alive unnecessarily.
        lastParams = null
        userInitiatedDisconnect = false
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            operationCoordinator.withExclusiveOperation connect@{
                val prefs = runCatchingNonCancellable { preferencesRepository.preferences.first() }.getOrNull()
                if (prefs != null) {
                    currentLanguageTag = prefs.languageTag
                    autoReconnectEnabled = prefs.autoReconnect
                }
                val entity = loadConnectionEntityOrFail(id) ?: return@connect
                PocketLogger.i(TAG, "launchConnect id=$id endpoint=<redacted>")
                val config = configureSessionFromEntity(entity)
                val driveDir = when (val drivePlan = resolveDriveRedirectionPlan(config.effectiveRedirectFiles)) {
                    RdpDriveRedirectionPlan.Disabled -> null
                    RdpDriveRedirectionPlan.NeedsAllFilesAccess -> {
                        PocketLogger.w(
                            TAG,
                            "folder redirection requested without MANAGE_EXTERNAL_STORAGE; waiting before connect",
                        )
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Idle,
                                filesRedirectEnabled = true,
                                allFilesAccessRequired = true,
                                lastError = null,
                            )
                        }
                        return@connect
                    }
                    is RdpDriveRedirectionPlan.Unavailable -> {
                        PocketLogger.e(TAG, "folder redirection path unavailable")
                        _state.update {
                            it.copy(
                                status = SessionConnectionStatus.Failed(drivePlan.reason),
                                filesRedirectEnabled = true,
                                allFilesAccessRequired = false,
                                lastError = drivePlan.reason,
                            )
                        }
                        return@connect
                    }
                    is RdpDriveRedirectionPlan.Ready -> {
                        PocketLogger.i(TAG, "folder redirection drive path ready")
                        drivePlan.path
                    }
                }
                val plain = decryptPasswordOrFail(entity) ?: return@connect
                if (!isActive || userInitiatedDisconnect) return@connect
                val autoReconnect = autoReconnectEnabled
                val params = RdpConnectionParams(
                    connectionId = entity.id,
                    displayName = entity.name,
                    host = entity.host,
                    port = entity.port,
                    username = entity.username,
                    domain = entity.domain,
                    password = plain,
                    colorDepth = entity.colorDepth,
                    useH264 = entity.useH264,
                    preferAvc420 = entity.preferAvc420,
                    useGfx = entity.useGfx,
                    dynamicResolution = config.effectiveDynamic,
                    useMultitransport = entity.useMultitransport && !udpDowngradedForSession,
                    redirectClipboard = entity.redirectClipboard,
                    redirectFiles = config.effectiveRedirectFiles,
                    drivePath = driveDir,
                    soundMode = entity.soundMode,
                    desktopScaleFactor = entity.desktopScaleFactor,
                    initialWidth = config.initialWidth,
                    initialHeight = config.initialHeight,
                    acceptedCertThumbprint = entity.certThumbSha256,
                    performanceFlags = entity.performanceFlags,
                    autoReconnect = autoReconnect,
                )
                lastParams = if (autoReconnect) params else null
                certificateDecisionPending = false
                reconnectAttempt = 0
                reconnectJob?.cancel()
                withContext(Dispatchers.IO) {
                    rdpClient.connect(params)
                }
                if (rdpClient.hasActiveSession()) {
                    startKeepAlive()
                }
                runCatchingNonCancellable { withContext(Dispatchers.IO) { repository.touchLastUsed(entity.id) } }
                    .onFailure { error ->
                        PocketLogger.w(TAG, "could not update connection last-used time", error)
                    }
            }
        }
    }

    /** Start the keep-alive foreground service for the current connection. */
    private fun startKeepAlive() {
        runCatching { RdpSessionService.start(appContext) }
            .onSuccess {
                // Mark "a session is meant to be alive" only after Android accepted the service
                // start. Otherwise a synchronous FGS-start failure looks like a later process kill.
                SessionKeepAliveFlag.setActive(appContext, true)
            }
            .onFailure {
                rdpClient.runIfNoActiveSessions {
                    SessionKeepAliveFlag.setActive(appContext, false)
                }
                PocketLogger.w(TAG, "startForegroundService failed: ${it.message}")
            }
    }

    /** Stop the keep-alive service only when this was the last active session. */
    private fun stopKeepAlive() {
        reconnectJob?.cancel()
        rdpClient.runIfNoActiveSessions {
            // Clean teardown — clear the flag so we DON'T later mistake this for a background kill.
            SessionKeepAliveFlag.setActive(appContext, false)
            RdpSessionService.stop(appContext)
        }
    }

    /**
     * Cancel any pending auto-reconnect timer and drop retained credentials when the user disables
     * auto-reconnect in Settings. If the session is idle between backoff attempts, leave
     * [SessionConnectionStatus.Reconnecting] and release keep-alive locks; live/in-flight sessions
     * are not torn down.
     */
    private fun cancelPendingAutoReconnect() {
        lastParams = null
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        if (rdpClient.clearReconnectingSession()) {
            _state.update {
                it.copy(status = statusAfterAutoReconnectCancelled(it.status, it.lastError))
            }
            stopKeepAlive()
        }
    }

    /**
     * Schedule an auto-reconnect after an UNEXPECTED drop / failed attempt, with exponential
     * backoff. Gives up (and stops the keep-alive service) after [RECONNECT_BACKOFF_MS].size tries.
     * No-op if auto-reconnect is off, the teardown was user-initiated, or we have no params.
     */
    private fun scheduleReconnect() {
        val baseParams = lastParams
        val canAutoReconnect = shouldScheduleAutoReconnect(
            autoReconnectEnabled = autoReconnectEnabled,
            eventRetryable = true,
            hasReconnectParams = baseParams != null,
            userInitiatedDisconnect = userInitiatedDisconnect,
            certificateDecisionPending = certificateDecisionPending,
        )
        if (!canAutoReconnect || baseParams == null) {
            reconnectJob?.cancel()
            if (!certificateDecisionPending) {
                lastParams = null
                rdpClient.clearReconnectingSession()
                stopKeepAlive()
            }
            return
        }
        val params = if (udpDowngradedForSession) {
            baseParams.copy(useMultitransport = false)
        } else {
            baseParams
        }
        lastParams = params
        reconnectJob?.cancel()
        if (reconnectAttempt >= RECONNECT_BACKOFF_MS.size) {
            PocketLogger.w(TAG, "auto-reconnect exhausted after $reconnectAttempt tries — giving up")
            userInitiatedDisconnect = true
            lastParams = null
            rdpClient.clearReconnectingSession()
            rdpClient.buffer.release()
            stopKeepAlive()
            return
        }
        val delayMs = RECONNECT_BACKOFF_MS[reconnectAttempt]
        reconnectAttempt++
        PocketLogger.i(TAG, "auto-reconnect attempt #$reconnectAttempt in ${delayMs}ms")
        _state.update { it.copy(status = SessionConnectionStatus.Reconnecting) }
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            val aborted = !isActive || userInitiatedDisconnect || !autoReconnectEnabled
            if (aborted) return@launch
            val retryParams = if (udpDowngradedForSession) {
                params.copy(useMultitransport = false, autoReconnect = true)
            } else {
                params.copy(autoReconnect = true)
            }
            // The keep-alive service stays running across the drop (we only stopSelf on a user
            // disconnect), so just re-issue the connect — the service updates its own notification.
            withContext(Dispatchers.IO) {
                rdpClient.connect(retryParams)
            }
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
        val wasImeVisible = _state.value.imeVisible
        _state.update {
            val newVisible = !it.chromeVisible
            // Hiding chrome also dismisses the keyboard/toolbar; restoring chrome leaves IME as-is.
            it.copy(chromeVisible = newVisible, imeVisible = if (newVisible) it.imeVisible else false)
        }
        if (wasImeVisible && !_state.value.imeVisible) {
            flushDeferredMonitorLayoutIfNeeded()
        }
    }

    fun setImeVisible(visible: Boolean) {
        val wasVisible = _state.value.imeVisible
        _state.update { it.copy(imeVisible = visible) }
        if (wasVisible && !visible) {
            flushDeferredMonitorLayoutIfNeeded()
        }
    }

    fun toggleIme() {
        val wasVisible = _state.value.imeVisible
        _state.update { it.copy(imeVisible = !it.imeVisible) }
        if (wasVisible && !_state.value.imeVisible) {
            flushDeferredMonitorLayoutIfNeeded()
        }
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

    /** Send a one-shot key chord without disturbing already latched toolbar modifiers. */
    fun sendKeyWithModifiers(vk: Int, modifierMask: Int) {
        sendKeyChordNow(vk, modifierMask)
    }

    private fun sendKeyChordNow(vk: Int, modifierMask: Int) {
        val stickyMask = _state.value.stickyModifiers
        val transientModifiers = toolbarModifierOrder
            .filter { flag -> modifierMask and flag != 0 && stickyMask and flag == 0 }
            .mapNotNull { flag -> ScancodeMap.Modifier.vkFor(flag) }

        transientModifiers.forEach { modVk -> rdpClient.sendKeyEvent(modVk, true) }
        rdpClient.sendKeyEvent(vk, true)
        rdpClient.sendKeyEvent(vk, false)
        transientModifiers.asReversed().forEach { modVk -> rdpClient.sendKeyEvent(modVk, false) }
    }

    /** Forward a discrete VK down/up — used by the IME bridge's onPreviewKeyEvent path. */
    fun sendVkRaw(vk: Int, down: Boolean) {
        if (!isProtectedStickyModifierKey(vk)) {
            rdpClient.sendKeyEvent(vk, down)
        }
    }

    fun sendCtrlAltDel() {
        sendKeyChordNow(
            vk = ScancodeMap.VK.DELETE,
            modifierMask = ScancodeMap.Modifier.CTRL or ScancodeMap.Modifier.ALT,
        )
    }

    private fun isProtectedStickyModifierKey(vk: Int): Boolean {
        val stickyMask = _state.value.stickyModifiers
        return toolbarModifierOrder.any { flag ->
            stickyMask and flag != 0 && ScancodeMap.Modifier.vkFor(flag) == vk
        }
    }

    private fun clearStickyModifiers(sendReleases: Boolean) {
        val stickyMask = _state.value.stickyModifiers
        if (sendReleases) {
            toolbarModifierOrder.asReversed()
                .filter { flag -> stickyMask and flag != 0 }
                .mapNotNull(ScancodeMap.Modifier::vkFor)
                .forEach { vk -> rdpClient.sendKeyEvent(vk, false) }
        }
        if (stickyMask != 0) {
            _state.update { it.copy(stickyModifiers = 0) }
        }
    }

    fun setFunctionToolbarQuickIds(ids: List<String>) {
        viewModelScope.launch {
            preferencesRepository.setFunctionToolbarQuickIds(ids)
        }
    }

    fun typeText(rawText: String) {
        if (rawText.isEmpty() || !canQueueTextInput(_state.value.status)) {
            return
        }
        val generation = inputGeneration
        // A third-party IME can commit an arbitrarily large CharSequence in one callback. Bound it
        // before line-ending normalization allocates another copy and before the bounded *message*
        // channel retains it. Together with the eight-slot queue this bounds retained IME-controlled
        // UTF-16 payload to roughly 1 MiB rather than allowing tens of MiB of process-heap pressure.
        val boundedRaw = truncateClipboardText(rawText, TYPE_TEXT_MAX_CHARS)
        if (boundedRaw.length != rawText.length) {
            PocketLogger.w(TAG, "IME text exceeded the safe size cap; truncating")
        }
        // Normalise line breaks to a single '\n' first so a CRLF never becomes a DOUBLE Enter in the
        // fallback typing path; the clipboard path re-expands to CRLF for Windows.
        val text = boundedRaw.replace("\r\n", "\n").replace('\r', '\n')
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
        // Single newline commits (soft-keyboard Enter) go through the typing path as VK.RETURN so
        // they never overwrite the remote clipboard or incur the 400 ms Ctrl+V paste delay.
        // Multi-line or large pastes still route through remote clipboard + Ctrl+V when enabled.
        if (shouldPasteTextViaClipboard(text, clipboardSyncEnabled)) {
            typeChannel.trySend(TextInputCommand.Paste(text, generation))
        } else {
            // Offer the chunk to the background type consumer (startTypeConsumer). trySend never blocks;
            // the bounded channel is drained by ONE ordered consumer that paces the keystrokes.
            // Under an abusive IME flood, oldest queued chunks are discarded instead of allowing
            // attacker-controlled text to grow process memory without bound.
            // Per-char routing (ASCII → scancode, else unicode) lives in TextInputEncoder.
            typeChannel.trySend(TextInputCommand.Type(text, generation))
        }
    }

    fun acceptPendingCertificate() {
        val pending = _state.value.pendingCertificate ?: return
        val connectionId = _state.value.connectionId
        if (connectionId <= 0L || pending.sha256.length != SHA256_HEX_LENGTH) return
        // Remove the dialog before starting I/O so repeated taps cannot save/launch the same trust
        // decision twice. A recoverable database error restores it below.
        _state.update {
            it.copy(
                status = SessionConnectionStatus.Connecting,
                pendingCertificate = null,
                pendingCredentialsPrompt = null,
                lastError = null,
            )
        }
        viewModelScope.launch {
            val endpointMatches = runCatching {
                repository.setCertThumbprintIfEndpointMatches(
                    id = connectionId,
                    expectedHost = pending.host,
                    expectedPort = pending.port,
                    thumb = pending.sha256,
                )
            }.getOrElse { error ->
                PocketLogger.e(TAG, "certificate pin could not be saved", error)
                val message = localizedString(R.string.session_certificate_save_failed)
                _state.update {
                    it.copy(
                        status = SessionConnectionStatus.Failed(message),
                        pendingCertificate = pending,
                        lastError = message,
                    )
                }
                return@launch
            }
            if (!endpointMatches) {
                certificateDecisionPending = false
                val message = localizedString(R.string.session_certificate_endpoint_changed)
                _state.update {
                    it.copy(
                        status = SessionConnectionStatus.Failed(message),
                        pendingCertificate = null,
                        lastError = message,
                    )
                }
                return@launch
            }
            certificateDecisionPending = false
            _state.update {
                it.copy(
                    status = SessionConnectionStatus.Connecting,
                    pendingCertificate = null,
                    lastError = null,
                )
            }
            launchConnect(connectionId)
        }
    }

    fun rejectPendingCertificate() {
        if (_state.value.pendingCertificate == null) return
        certificateDecisionPending = false
        userInitiatedDisconnect = true
        lastParams = null
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        stopTypeConsumer()
        clearStickyModifiers(sendReleases = true)
        val rejectedMessage = localizedString(R.string.session_certificate_rejected)
        _state.update {
            it.copy(
                status = SessionConnectionStatus.Failed(rejectedMessage),
                imeVisible = false,
                pendingCertificate = null,
                pendingCredentialsPrompt = null,
                lastError = rejectedMessage,
            )
        }
        // RdpClient emits a final user-disconnect event while removing the registry entry. Publish
        // the explicit rejection first; statusAfterDisconnect preserves that terminal explanation
        // instead of replacing it with a generic "Disconnected" state one main-loop turn later.
        rdpClient.disconnect()
        stopKeepAlive()
    }

    /**
     * Paste a block of text into the remote by putting it on the remote clipboard and sending Ctrl+V,
     * instead of typing it out. This is how a large / multi-line paste is delivered (see [typeText]):
     * it preserves newlines as text and sends only 4 key events, so it neither floods the input channel
     * (断开/闪退) nor turns newlines into Enter (换行变回车). The cliprdr push is async, so we wait briefly
     * before the paste so the remote clipboard holds the new text first.
     */
    private suspend fun pasteViaRemoteClipboard(text: String, generation: Long) {
        if (generation != inputGeneration) return
            // CRLF so Windows apps render the line breaks (a bare \n shows as no break in Notepad etc.),
            // and cap the length the same way the clipboard listener does.
        val crlf = toWindowsClipboardText(text)
        val payload = truncateClipboardText(crlf, CLIPBOARD_MAX_CHARS)
            // Echo guard: mark before sending so the server echoing this text back (ClipboardReceived)
            // — and our own clipboard listener — recognise it and don't bounce it around.
        lastRemoteClipboardPayload = payload
        rdpClient.sendClipboard(payload)
        delay(CLIPBOARD_PASTE_DELAY_MS)
        if (generation != inputGeneration) return
        val vKey = ScancodeMap.asciiVkFor('v'.code)?.first ?: VK_V_FALLBACK
        sendKeyChordNow(vKey, ScancodeMap.Modifier.CTRL)
    }

    private fun drainTypeQueue() {
        while (typeChannel.tryReceive().isSuccess) {
            // Drop input queued for a session that is no longer connected.
        }
    }

    private fun stopTypeConsumer() {
        inputGeneration++
        typeConsumerJob?.cancel()
        typeConsumerJob = null
        drainTypeQueue()
        // These loop-suppression tokens can contain clipboard or pasted secrets. They are useful
        // only while this native cliprdr session is alive; do not retain them across teardown or
        // reconnect in the retained ViewModel.
        lastRemoteClipboardPayload = ""
        lastAppliedRemoteClipboard = ""
    }

    fun onSurfaceResized(width: Int, height: Int) {
        lastSurfaceWidth = width
        lastSurfaceHeight = height
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
        if (_state.value.imeVisible) {
            pendingMonitorResizeDeferred = true
        }
        dispatchPendingMonitorLayoutDebounced()
    }

    private fun flushDeferredMonitorLayoutIfNeeded() {
        val current = _state.value
        val shouldFlush = shouldFlushDeferredMonitorLayout(
            dynamicResolutionEnabled = dynamicResolutionEnabled,
            imeVisible = current.imeVisible,
            status = current.status,
            pendingSize = pendingMonitorWidth to pendingMonitorHeight,
            remoteSize = current.remoteWidth to current.remoteHeight,
            resizeDeferred = pendingMonitorResizeDeferred,
        )
        if (!shouldFlush) return
        pendingMonitorResizeDeferred = false
        dispatchPendingMonitorLayoutDebounced()
    }

    private fun dispatchPendingMonitorLayoutDebounced() {
        // Collapse keyboard-animation resize bursts. We log only the post-debounce dispatch so
        // logs stay readable; the burst itself produces no spam.
        monitorLayoutDebounceJob?.cancel()
        monitorLayoutDebounceJob = viewModelScope.launch {
            delay(MONITOR_LAYOUT_DEBOUNCE_MS)
            if (!dynamicResolutionEnabled) return@launch
            // Don't resize the remote just because the soft keyboard shrank the local viewport —
            // wait until the keyboard is dismissed and the view returns to its full size.
            if (_state.value.imeVisible) {
                pendingMonitorResizeDeferred = true
                return@launch
            }
            val w = pendingMonitorWidth
            val h = pendingMonitorHeight
            if (w <= 0 || h <= 0) return@launch
            val current = _state.value
            if (current.status is SessionConnectionStatus.Connected &&
                isMonitorLayoutMatchingRemote(w, h, current.remoteWidth, current.remoteHeight)
            ) {
                pendingMonitorResizeDeferred = false
                return@launch
            }
            pendingMonitorResizeDeferred = false
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
                // The keyboard temporarily shrinks the AndroidView. onSurfaceResized deliberately
                // records that latest size but does not dispatch it while the IME is visible; the
                // retry loop must honor the same rule after a reconnect, otherwise it can resize
                // the remote desktop to the keyboard-compressed viewport.
                val w = pendingMonitorWidth
                val h = pendingMonitorHeight
                if (!shouldDispatchMonitorLayoutRetry(_state.value.imeVisible, w, h)) continue
                val ok = rdpClient.sendMonitorLayout(w, h)
                PocketLogger.i(TAG, "monitor-layout retry #$idx ${w}x$h (after ${ms}ms) ok=$ok")
                // Don't break on ok==true: even if the JNI accepted it, the server might
                // still drop it (PDU sent before its own disp client finished init). We
                // stop only when the matching OnGraphicsResize comes back.
            }
            PocketLogger.w(TAG, "monitor-layout retry schedule exhausted; server never acknowledged")
        }
    }

    /** Start mirroring local clipboard changes to the remote (idempotent; no-op when sync is off). */
    private fun registerClipboardListener() {
        if (!clipboardSyncEnabled || clipListenerRegistered) return
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
        // Explicit user teardown (disconnect button): stop reconnecting and tear down only this
        // session. The keep-alive service stops only if no other RdpClient remains active.
        userInitiatedDisconnect = true
        // RdpConnectionParams contains the decrypted password solely for auto-reconnect. Once the
        // user explicitly ends the session, release that reference immediately.
        lastParams = null
        connectJob?.cancel()
        connectJob = null
        // Grab a final desktop thumbnail BEFORE rdpClient.disconnect() releases the buffer — the
        // snapshot is an independent copy, so the subsequent release is harmless to it.
        captureThumbnail()
        stopTypeConsumer()
        clearStickyModifiers(sendReleases = true)
        _state.update { it.copy(imeVisible = false, pendingCredentialsPrompt = null) }
        rdpClient.disconnect()
        stopKeepAlive()
    }

    override fun onCleared() {
        // M7 keep-alive: a *backgrounded* session does NOT reach here — single-Activity Compose nav
        // keeps the ViewModel across onStop, and the RdpSessionService keeps the process alive. This
        // fires only when the user actually LEAVES the session screen (pops the back stack), which
        // IS a deliberate teardown for this session. Other active sessions stay alive.
        PocketLogger.i(TAG, "SessionViewModel.onCleared — disconnecting + stopping keep-alive")
        userInitiatedDisconnect = true
        lastParams = null
        connectJob?.cancel()
        connectJob = null
        // Last chance to refresh the list thumbnail (still Connected here); the store encodes on its
        // own scope, so it survives this ViewModel's scope being cancelled right after onCleared.
        captureThumbnail()
        unregisterClipboardListener()
        stopTypeConsumer()
        clearStickyModifiers(sendReleases = true)
        typeChannel.close()
        rdpClient.disconnect()
        stopKeepAlive()
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
        private const val TYPE_TEXT_MAX_CHARS = 64 * 1024
        private const val TYPE_QUEUE_CAPACITY = 8
        private const val SHA256_HEX_LENGTH = 64
        // typeText: a chunk longer than this (or containing a multi-line break) is treated as a PASTE
        // and delivered via the remote clipboard + Ctrl+V instead of char-by-char typing.
        // High enough that normal typing / IME word commits never trip it; low enough to catch real
        // pastes well before they could flood the input channel.
        internal const val PASTE_VIA_CLIPBOARD_THRESHOLD = 200
        internal const val MONITOR_LAYOUT_ACK_TOLERANCE_PX = 8
        // Wait after pushing the clipboard before sending Ctrl+V, so the async cliprdr round-trip has
        // updated the remote clipboard first. (The text was usually already synced when the user copied,
        // so this is mostly insurance.)
        private const val CLIPBOARD_PASTE_DELAY_MS = 400L
        // VK_V (0x56) fallback if the ASCII map ever fails to resolve 'v'.
        private const val VK_V_FALLBACK = 0x56
        // Active cards use memory-backed live previews and normal teardown always performs a final
        // capture. Persist only a low-frequency crash/restart fallback: a 12-second interval could
        // write well over a gigabyte of JPEG data during a day-long RDP session.
        private const val THUMB_CAPTURE_INTERVAL_MS = 5 * 60_000L
        // Longest edge (px) of the stored thumbnail. Bumped 640 → 1280 to kill the connection-card
        // blur (用户反馈: 主页图片非常模糊): the card spans almost the full screen width, so a 640px source
        // was upscaled ~2× and looked soft. 1280px (paired with JPEG quality 92, see ConnectionThumbnail
        // Store) shows ~1:1 on phones and stays ~100–200 KB. snapshot() never upscales beyond the
        // framebuffer, so a sub-1280 remote desktop just stores at its own size.
        private const val THUMB_MAX_DIM = 1280
        // Dynamic-resolution default initial size (issue: 1920×1080 fallback).
        private const val DEFAULT_DYNAMIC_WIDTH = 1920
        private const val DEFAULT_DYNAMIC_HEIGHT = 1080
        // Auto-reconnect backoff after an unexpected drop / failed attempt (ms). The array length
        // also caps the number of tries — after the last one we give up and stop the keep-alive
        // service. Resets to attempt 0 on every successful Connected.
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}

internal fun shouldLaunchConnect(
    current: SessionUiState,
    requestedConnectionId: Long,
    allowStoppedSessionRestart: Boolean = true,
): Boolean {
    if (requestedConnectionId <= 0L) return false
    val hasPendingPrompt = current.pendingCertificate != null || current.pendingCredentialsPrompt != null
    if (current.connectionId == requestedConnectionId && hasPendingPrompt) return false
    if (current.connectionId != requestedConnectionId) return true
    // retryAfterAllFilesAccess() owns this transition and first verifies that the grant really
    // exists. A generic lifecycle callback must not spin repository/preflight work whenever the
    // user returns from Settings without granting broad storage access.
    if (current.allFilesAccessRequired) return false
    return when (current.status) {
        SessionConnectionStatus.Idle -> true
        is SessionConnectionStatus.Disconnected,
        is SessionConnectionStatus.Failed -> allowStoppedSessionRestart
        SessionConnectionStatus.Connecting,
        SessionConnectionStatus.Reconnecting,
        SessionConnectionStatus.Connected -> false
    }
}

internal fun isUdpDowngradeObserved(snapshot: RdpTransportSnapshot): Boolean {
    val failedPhase = snapshot.phase == RdpTransportPhase.RECONNECTING_TCP ||
        snapshot.phase == RdpTransportPhase.UDP_FAILED
    return snapshot.downgradedToTcp || snapshot.reconnectRequested || failedPhase
}

internal fun shouldPasteTextViaClipboard(
    normalizedText: String,
    clipboardSyncEnabled: Boolean,
): Boolean {
    if (!clipboardSyncEnabled || normalizedText == "\n") return false
    return normalizedText.length > SessionViewModel.PASTE_VIA_CLIPBOARD_THRESHOLD ||
        normalizedText.contains('\n')
}

internal fun shouldFlushDeferredMonitorLayout(
    dynamicResolutionEnabled: Boolean,
    imeVisible: Boolean,
    status: SessionConnectionStatus,
    pendingSize: Pair<Int, Int>,
    remoteSize: Pair<Int, Int>,
    resizeDeferred: Boolean,
): Boolean {
    val canResize = dynamicResolutionEnabled && !imeVisible && status is SessionConnectionStatus.Connected
    val hasPendingSize = pendingSize.first > 0 && pendingSize.second > 0
    if (!canResize || !hasPendingSize) return false
    if (resizeDeferred) return true
    val widthDiff = (remoteSize.first - pendingSize.first).absoluteValue
    val heightDiff = (remoteSize.second - pendingSize.second).absoluteValue
    return widthDiff > SessionViewModel.MONITOR_LAYOUT_ACK_TOLERANCE_PX ||
        heightDiff > SessionViewModel.MONITOR_LAYOUT_ACK_TOLERANCE_PX
}

internal fun canQueueTextInput(status: SessionConnectionStatus): Boolean =
    status is SessionConnectionStatus.Connected

internal fun statusAfterDisconnect(
    currentStatus: SessionConnectionStatus,
    reason: String?,
): SessionConnectionStatus =
    if (reason == "user" && currentStatus is SessionConnectionStatus.Failed) {
        currentStatus
    } else {
        SessionConnectionStatus.Disconnected(reason)
    }

internal fun isMonitorLayoutMatchingRemote(
    targetWidth: Int,
    targetHeight: Int,
    remoteWidth: Int,
    remoteHeight: Int,
): Boolean {
    val hasTarget = targetWidth > 0 && targetHeight > 0
    val hasRemote = remoteWidth > 0 && remoteHeight > 0
    if (!hasTarget || !hasRemote) return false
    val widthDiff = (remoteWidth - targetWidth).absoluteValue
    val heightDiff = (remoteHeight - targetHeight).absoluteValue
    return widthDiff <= SessionViewModel.MONITOR_LAYOUT_ACK_TOLERANCE_PX &&
        heightDiff <= SessionViewModel.MONITOR_LAYOUT_ACK_TOLERANCE_PX
}

internal fun shouldDispatchMonitorLayoutRetry(
    imeVisible: Boolean,
    width: Int,
    height: Int,
): Boolean = !imeVisible && width > 0 && height > 0
