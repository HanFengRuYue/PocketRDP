package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Kotlin facade over the FreeRDP JNI bridge (com.freerdp.freerdpcore.services.LibFreeRDP).
 *
 * The bridge calls static callbacks (OnConnectionSuccess, OnGraphicsUpdate, ...), then routes
 * each callback through an instance-keyed listener registration into this client's state streams
 * and bitmap buffer. There is deliberately no process-wide fallback listener: late callbacks from
 * one native instance must never be delivered to another retained session.
 *
 * Bitmap flow note: native side does NOT push frame bytes. Instead it tells us *where* the
 * dirty region is via OnGraphicsUpdate, and we pull pixels into our own Bitmap by calling
 * LibFreeRDP.updateGraphics(inst, bitmap, x, y, w, h). See android_freerdp.c.
 */
class RdpClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRegistry: RdpSessionRegistry,
) {

    val buffer: BitmapBuffer = BitmapBuffer()

    // Connection/security events are lossless and ordered. High-rate or remotely-triggerable
    // graphics, cursor and clipboard updates use separate conflated streams below, so they cannot
    // crowd a certificate prompt or disconnect out of this channel.
    private val eventChannel = Channel<RdpEvent>(capacity = Channel.UNLIMITED)
    val events: Flow<RdpEvent> = eventChannel.receiveAsFlow()
    private val _frameUpdates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frameUpdates: SharedFlow<Unit> = _frameUpdates.asSharedFlow()
    private val _clipboardUpdates = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val clipboardUpdates: SharedFlow<String> = _clipboardUpdates.asSharedFlow()
    private val _resizeUpdates = MutableSharedFlow<RdpResize>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resizeUpdates: SharedFlow<RdpResize> = _resizeUpdates.asSharedFlow()
    private val _remoteCursor = MutableStateFlow<RdpCursor>(RdpCursor.Default)
    val remoteCursor = _remoteCursor.asStateFlow()

    /**
     * Leases every JNI call that dereferences a native instance against detach/free. A volatile
     * read alone is not enough: teardown could free the pointer after a sender reads it but before
     * the JNI call begins. Calls may run concurrently, but teardown waits for all leases to drain.
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val nativeCallLock = java.lang.Object()
    private var inFlightNativeCalls: Int = 0
    @Volatile private var nativeInstance: Long = 0L
    private var connectThread: Thread? = null
    @Volatile private var acceptedCertThumb: String? = null
    @Volatile private var certificatePromptOutstanding: Boolean = false
    @Volatile private var certificateFatalFailureOutstanding: Boolean = false

    // RDP-UDP diagnostics are derived from the native atomic snapshot and written through
    // PocketLogger so a user-exported application log contains the negotiation history. Only
    // protocol state, numeric errors and aggregate counters are retained; endpoint/security data
    // is never accepted by the formatter.
    private val transportDiagnosticLock = Any()
    private var multitransportRequestedForConnection: Boolean = false
    private var transportConnectedAtMs: Long = 0L
    private var transportRequestObserved: Boolean = false
    private var missingTransportRequestLogged: Boolean = false
    private var incompatibleTransportSnapshotLogged: Boolean = false
    private var lastLoggedTransportControl: String? = null
    private var lastTransportTrafficLogMs: Long = 0L

    // Host/port of the active session — used by [measureLatencyMs] for the latency probe.
    @Volatile private var lastHost: String? = null
    @Volatile private var lastPort: Int = DEFAULT_RDP_PORT

    // --- End-to-end "control latency" (input → on-screen change) ---
    // measureLatencyMs() below only times a TCP handshake = the raw network path. The latency the
    // user actually FEELS is much higher: input upload + server input-processing + server-side
    // encode + downstream network + client H.264 decode (the gdi buffer is already decoded by the
    // time OnGraphicsUpdate fires). We measure that round-trip empirically: a DISCRETE press (click /
    // key / touch — see markDiscreteInput) made while the screen was idle is reliably answered by a
    // framebuffer update, so (frameArrival − inputSent) is a real end-to-end RTT that inherently
    // includes encode/decode. CURSOR MOVES are deliberately excluded: RDP acks them via a pointer PDU
    // (hardware cursor), NOT a framebuffer update, so timing move→frame paired a move with a much-
    // later unrelated frame and read hundreds of ms on a 0ms-RTT local host. Presses that get no
    // timely frame are discarded (CONTROL_LATENCY_RESPONSE_WINDOW_MS); the rest feed a recent-sample
    // low-percentile estimator (robust to periodic-frame mispairs). Monotonic clock so a wall-clock
    // change can't corrupt a sample.
    @Volatile private var pendingInputAtMs = 0L
    @Volatile private var lastServerFrameAtMs = 0L
    // Recent accepted input→frame samples (ms). We report a LOW PERCENTILE (P25) of these — NOT an
    // EMA-of-all (which a terminal's periodic blink frames inflated to 330ms against a 40ms RTT) and
    // NOT a hard minimum (a blink frame landing right after a press would lock in a bogus ~0ms floor).
    // The low end of recent samples is the fastest a frame can follow an input = the trustworthy
    // estimate; periodic mispairs are always slower and fall in the tail, so P25 rejects both artifacts.
    private val latencyLock = Any()
    private val latencySamples = IntArray(CONTROL_LATENCY_SAMPLE_WINDOW)
    private var latencySampleCount = 0
    private var latencySampleHead = 0
    // Diagnostic counters (METRIC-4): how many discrete-input samples were accepted (a timely frame
    // closed them) vs discarded (no frame within CONTROL_LATENCY_RESPONSE_WINDOW_MS → the press caused
    // no visible change / we'd have mispaired). A high discard ratio means presses aren't producing
    // frames — the latency is server-side / the screen is inert, NOT the client being slow.
    @Volatile private var latencyAcceptedCount = 0
    @Volatile private var latencyDiscardedCount = 0

    // --- Display-pipeline latency (decode→present), the part controlLatencyMs does NOT measure ---
    // RdpSurface reports, for each newly-drawn frame, how long it sat between commitFrame (decode done,
    // back→front swap) and actually being blitted on screen (commit→onDraw self-loop tick + the canvas
    // record; the async RenderThread texture upload after this is still uncounted). Felt latency ≈
    // controlLatencyMs (input→decode) + presentLagMs (decode→present). A rolling median over recent
    // frames rejects the odd outlier. Fed by [recordPresentLag] from the UI/RenderThread; reset on connect.
    private val presentLagLock = Any()
    private val presentLagSamples = IntArray(PRESENT_LAG_SAMPLE_WINDOW)
    private var presentLagCount = 0
    private var presentLagHead = 0

    // Diagnostic: throttled "frames are still arriving" heartbeat. OnGraphicsUpdate is too frequent
    // to log per-call, but if the remote picture FREEZES (no graphics after the initial resize) the
    // TCP connection goes idle and a mobile NAT / port-forward drops it after ~18 s — which reads as
    // "连上几秒就断开". Logging a count every GFX_LOG_INTERVAL_MS lets a bug report distinguish
    // "frozen picture → idle drop" from "live picture → network drop": if these lines stop well
    // before OnDisconnecting, the picture froze; if they continue right up to it, the link dropped.
    @Volatile private var gfxUpdateCount: Int = 0
    @Volatile private var lastGfxLogMs: Long = 0L

    // Throttled keyboard-input debug counters. Logging every keystroke would drown the log
    // viewer when the user holds a key or pastes long text; logging nothing makes it
    // impossible to verify "keyboard input → disconnect" timing in a bug report. We log the
    // first N events per session to capture the start-of-typing pattern, then go silent.
    private var keyEventLogCount: Int = 0
    private var unicodeEventLogCount: Int = 0

    fun connect(params: RdpConnectionParams) {
        PocketLogger.i(
            TAG,
            "connect() endpoint=<redacted> h264=${params.useH264} gfx=${params.useGfx} " +
                "dynRes=${params.dynamicResolution} multitransport=${params.useMultitransport}",
        )
        if (!LibFreeRDP.isNativeReady()) {
            PocketLogger.e(TAG, "native FreeRDP not ready — refusing connect")
            emit(
                RdpEvent.Failed(
                    "native FreeRDP not built — see core-rdp/build.gradle.kts to enable CMake superbuild",
                    retryable = false,
                ),
            )
            return
        }
        val previous = synchronized(nativeCallLock) { nativeInstance }
        if (previous != 0L) {
            PocketLogger.w(TAG, "connect() called while previous native instance still alive — closing it first")
            closeNativeInstance(previous)
            buffer.release()
        }

        resetTransportDiagnostics(params.useMultitransport)
        if (params.useMultitransport) {
            PocketLogger.i(
                TAG,
                "RDP-UDP diagnostic: enabled for this attempt; waiting for a server multitransport request",
            )
        } else {
            PocketLogger.i(TAG, "RDP-UDP diagnostic: disabled for this attempt; TCP-only requested")
        }

        emit(RdpEvent.Connecting)
        acceptedCertThumb = params.acceptedCertThumbprint
            ?.let(::normalizeCertificateFingerprint)
            ?.takeIf(String::isNotEmpty)
        certificatePromptOutstanding = false
        certificateFatalFailureOutstanding = false
        lastHost = params.host
        lastPort = params.port
        val inst = LibFreeRDP.newInstance(context)
        if (inst == 0L) {
            PocketLogger.e(TAG, "LibFreeRDP.newInstance returned 0")
            emit(RdpEvent.Failed("freerdp_new returned 0", retryable = false))
            return
        }
        synchronized(nativeCallLock) {
            nativeInstance = inst
        }
        sessionRegistry.markConnecting(this, params)
        LibFreeRDP.registerEventListener(inst, eventListener)
        LibFreeRDP.registerUIEventListener(inst, uiEventListener)
        keyEventLogCount = 0
        unicodeEventLogCount = 0
        pendingInputAtMs = 0L
        lastServerFrameAtMs = 0L
        synchronized(latencyLock) { latencySampleCount = 0; latencySampleHead = 0 }
        latencyAcceptedCount = 0
        latencyDiscardedCount = 0
        synchronized(presentLagLock) { presentLagCount = 0; presentLagHead = 0 }
        gfxUpdateCount = 0
        lastGfxLogMs = 0L
        PocketLogger.d(TAG, "FreeRDP native instance allocated")

        val args = buildCommandLine(params)
        PocketLogger.i(TAG, "args=${redact(args).joinToString(" ")}")
        val parsed = withSpecificLiveInstance(inst) {
            LibFreeRDP.setConnectionArgs(inst, args)
        } ?: false
        if (!parsed) {
            val err = withSpecificLiveInstance(inst) {
                LibFreeRDP.freerdp_get_last_error_string(inst)
            }.orEmpty()
            PocketLogger.e(TAG, "freerdp_parse_arguments failed (last_error='$err')")
            emit(RdpEvent.Failed("freerdp_parse_arguments failed: $err", retryable = false))
            sessionRegistry.unregister(this)
            closeNativeInstance(inst)
            return
        }

        // JNI connect starts FreeRDP's native worker and returns. Keep the short startup call away
        // from the UI thread; the native worker's lifetime is joined by freerdp_free().
        connectThread = Thread({
            PocketLogger.d(TAG, "starting FreeRDP native worker")
            val ok = withSpecificLiveInstance(inst) { LibFreeRDP.connect(inst) } ?: false
            if (!ok) {
                val failure = withSpecificLiveInstance(inst) {
                    captureAndLogTransportSnapshot(inst, source = "connect-return", force = true)
                    LibFreeRDP.freerdp_get_last_error_code(inst) to
                        LibFreeRDP.freerdp_get_last_error_string(inst)
                }
                val errorCode = failure?.first ?: 0L
                val err = failure?.second.orEmpty()
                if (isCurrentInstance(inst)) {
                    when (
                        certificateTerminalDisposition(
                            promptOutstanding = certificatePromptOutstanding,
                            fatalFailureOutstanding = certificateFatalFailureOutstanding,
                        )
                    ) {
                        CertificateTerminalDisposition.PROMPT -> {
                            PocketLogger.i(TAG, "connection paused for certificate verification")
                            // There is no native connection or scheduled retry while the user
                            // decides. Keeping this client registered as RECONNECTING would leave
                            // the aggregate FGS and its CPU/Wi-Fi locks alive indefinitely.
                            sessionRegistry.unregister(this)
                            closeNativeInstance(inst)
                            return@Thread
                        }
                        CertificateTerminalDisposition.FATAL -> {
                            PocketLogger.e(TAG, "connection rejected because certificate data was invalid")
                            sessionRegistry.unregister(this)
                            closeNativeInstance(inst)
                            return@Thread
                        }
                        CertificateTerminalDisposition.NONE -> Unit
                    }
                    val retryable = isRetryableRdpFailure(errorCode)
                    PocketLogger.e(
                        TAG,
                        "freerdp_connect returned false (code=$errorCode, last_error='$err')",
                    )
                    if (retryable) {
                        sessionRegistry.markReconnecting(this)
                    } else {
                        sessionRegistry.unregister(this)
                    }
                    emit(
                        RdpEvent.Failed(
                            "freerdp_connect returned false: ${err.ifBlank { "unknown error" }}",
                            retryable = retryable,
                        ),
                    )
                    closeNativeInstance(inst)
                } else {
                    PocketLogger.d(TAG, "stale freerdp_connect return ignored")
                }
            }
        }, "freerdp-connect-start").also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        val inst = synchronized(nativeCallLock) { nativeInstance }
        if (inst == 0L) {
            if (sessionRegistry.contains(this)) {
                sessionRegistry.unregister(this)
                emit(RdpEvent.Disconnected(reason = "user"))
            }
            // A certificate-prompt terminal path unregisters before the user's decision. Rejection
            // still needs to clear any stale frame from the preceding connection.
            buffer.release()
            return
        }
        PocketLogger.i(TAG, "disconnect()")
        closeNativeInstance(inst)
        sessionRegistry.unregister(this)
        buffer.release()
        emit(RdpEvent.Disconnected(reason = "user"))
    }

    fun hasActiveSession(): Boolean = sessionRegistry.contains(this)

    fun runIfNoActiveSessions(action: () -> Unit): Boolean =
        sessionRegistry.runIfNoActiveSessions(action)

    private fun closeNativeInstance(inst: Long) {
        val detached = synchronized(nativeCallLock) {
            if (nativeInstance != inst) {
                false
            } else {
                nativeInstance = 0L
                while (inFlightNativeCalls > 0) {
                    nativeCallLock.wait()
                }
                true
            }
        }
        if (!detached) return
        LibFreeRDP.unregisterEventListener(inst)
        LibFreeRDP.unregisterUIEventListener(inst)
        Thread {
            // No new JNI call can acquire a lease after detach, and all earlier leases drained
            // above. Do not hold nativeCallLock while freeInstance joins the native worker: a
            // callback already in flight may still need to observe the detached state.
            LibFreeRDP.disconnect(inst)
            LibFreeRDP.freeInstance(inst)
        }.apply {
            name = "freerdp-cleanup"
            isDaemon = true
        }.start()
    }

    fun sendKeyEvent(scanCode: Int, down: Boolean) {
        if (!hasLiveInstance()) {
            // Caller is sending input after disconnect — silently drop. This used to push to
            // a freed native instance.
            PocketLogger.w(TAG, "keyboard event dropped (no native instance)")
            return
        }
        if (keyEventLogCount < KEY_LOG_LIMIT) {
            keyEventLogCount++
            PocketLogger.d(TAG, "keyboard event forwarded (#$keyEventLogCount)")
        }
        if (down) markDiscreteInput()
        withLiveInstance { inst -> LibFreeRDP.sendKeyEvent(inst, scanCode, down) }
    }

    fun sendUnicodeKey(codePoint: Int, down: Boolean) {
        if (!hasLiveInstance()) {
            PocketLogger.w(TAG, "unicode keyboard event dropped (no native instance)")
            return
        }
        if (unicodeEventLogCount < KEY_LOG_LIMIT) {
            unicodeEventLogCount++
            PocketLogger.d(TAG, "unicode keyboard event forwarded (#$unicodeEventLogCount)")
        }
        if (down) markDiscreteInput()
        withLiveInstance { inst -> LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, down) }
    }

    /**
     * Whether unicode keyboard input is enabled for this session (FreeRDP_UnicodeInput).
     *
     * The native layer (android_post_connect) now FORCE-ENABLES UnicodeInput=TRUE after the
     * capability exchange, overriding the server-capability downgrade that used to leave it FALSE
     * whenever a host didn't advertise INPUT_FLAG_UNICODE in its Demand Active caps. That downgrade
     * was why typing Chinese silently did nothing (every CJK code point was dropped here while ASCII
     * went out via the scancode path). So for a live, connected session this is now effectively
     * always true, and the Kotlin [TextInputEncoder] takes the unicode path for non-ASCII characters.
     *
     * It used to be fatal to send unicode to a non-advertising server — the rejected input PDU made
     * android_process_event break out of the connection loop ("type one character → instant
     * disconnect"). That path is now defused on both ends: the force-enable means the PDU is no
     * longer rejected, and android_event.c additionally treats a failed unicode-key send as
     * non-fatal (rc=TRUE), so even a server that genuinely can't decode it just drops the char.
     *
     * Returns false when there's no live instance, so input is naturally skipped after teardown.
     */
    fun isUnicodeInputSupported(): Boolean {
        return withLiveInstance { inst -> LibFreeRDP.isUnicodeInputSupported(inst) } ?: false
    }

    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        if (hasLiveInstance()) {
            // Latency timing: arm ONLY on a button PRESS (click / drag-start). A plain cursor MOVE is
            // acked by the server via a pointer PDU (hardware cursor), NOT a framebuffer update — so
            // timing move→OnGraphicsUpdate paired the move with a much-later unrelated frame (clock
            // tick, popup) and grossly inflated the reading (field bug: 操控延迟 484ms on a 0ms-RTT
            // local host). A press reliably triggers a visible response we can actually time.
            val isButtonPress = flags and RdpPointerFlags.DOWN != 0 &&
                flags and (RdpPointerFlags.BUTTON1 or RdpPointerFlags.BUTTON2 or RdpPointerFlags.BUTTON3) != 0
            if (isButtonPress) markDiscreteInput()
            withLiveInstance { inst -> LibFreeRDP.sendCursorEvent(inst, x, y, flags) }
        }
    }

    /**
     * Forward a native multi-touch contact (issue: 调用 Windows 原生触屏). [action] is one of
     * [RdpTouchAction] (DOWN/MOVE/UP); [contactId] is the finger id tracked across the gesture.
     * Silently dropped (returns) when there's no live instance or the rdpei channel isn't up yet —
     * never tears down the session.
     */
    fun sendTouch(contactId: Int, x: Int, y: Int, action: Int) {
        if (hasLiveInstance()) {
            // Same as cursor: arm latency only on a touch DOWN (the press), not MOVE/UP.
            if (action == RdpTouchAction.DOWN) markDiscreteInput()
            withLiveInstance { inst -> LibFreeRDP.sendTouch(inst, contactId, x, y, action) }
        }
    }

    /**
     * Arm an end-to-end latency sample for a DISCRETE input (click / key / touch press — NOT a cursor
     * move; see [sendCursorEvent]). If the screen has been quiet for [CONTROL_LATENCY_IDLE_GAP_MS],
     * the next server frame is a genuine response to this input, so we remember when it was sent.
     * Expires a stale pending arm first: a press that produced no timely frame (e.g. a click on inert
     * desktop) must NOT linger and later pair with an unrelated frame — that's what inflated the
     * reading. Only one input is pending at a time (paired with the next frame).
     */
    private fun markDiscreteInput() {
        val now = SystemClock.uptimeMillis()
        // Drop a pending arm that never got a timely response so it can't pair with a late frame.
        if (pendingInputAtMs != 0L && now - pendingInputAtMs > CONTROL_LATENCY_RESPONSE_WINDOW_MS) {
            pendingInputAtMs = 0L
        }
        if (pendingInputAtMs == 0L && now - lastServerFrameAtMs > CONTROL_LATENCY_IDLE_GAP_MS) {
            pendingInputAtMs = now
        }
    }

    /**
     * Smoothed end-to-end control latency (ms): input → on-screen frame, empirically including
     * server processing, encode, network and client decode. -1 until the first sample (or no live
     * session). This is the latency the user actually feels — far higher than [measureLatencyMs]'s
     * raw network RTT.
     */
    fun controlLatencyMs(): Int {
        if (!hasLiveInstance()) return -1
        synchronized(latencyLock) {
            val n = latencySampleCount
            if (n == 0) return -1
            val sorted = latencySamples.copyOf(n).also { it.sort() }
            // Low percentile (P25): rejects both the slow periodic-frame mispairs (tail) and a stray
            // near-zero from a blink landing right after a press (min). Index clamped into range.
            val idx = ((n - 1) * CONTROL_LATENCY_PERCENTILE).roundToInt().coerceIn(0, n - 1)
            return sorted[idx]
        }
    }

    /** Called when a decoded server frame lands (OnGraphicsUpdate). Closes a pending latency sample. */
    private fun recordServerFrameForLatency() {
        val now = SystemClock.uptimeMillis()
        lastServerFrameAtMs = now
        val sent = pendingInputAtMs
        if (sent == 0L) return
        pendingInputAtMs = 0L
        val sample = now - sent
        // Only accept a TIMELY response. A larger gap means the press caused no visible change and we
        // caught a later unrelated frame — discard rather than feed a bogus high sample. (The window is
        // deliberately not tighter than this: real felt latency can be a couple hundred ms, and a too-
        // tight window would starve the estimator of genuine samples.)
        if (sample < 0L || sample > CONTROL_LATENCY_RESPONSE_WINDOW_MS) {
            latencyDiscardedCount++
            return
        }
        latencyAcceptedCount++
        synchronized(latencyLock) {
            latencySamples[latencySampleHead] = sample.toInt()
            latencySampleHead = (latencySampleHead + 1) % latencySamples.size
            if (latencySampleCount < latencySamples.size) latencySampleCount++
        }
    }

    /** Accepted-sample count (a discrete input that got a timely frame) — METRIC-4 diagnostic. */
    fun latencyAccepted(): Int = latencyAcceptedCount

    /** Discarded-sample count (a discrete input with no frame within the window) — METRIC-4 diagnostic. */
    fun latencyDiscarded(): Int = latencyDiscardedCount

    /**
     * Record one decode→present delay (ms) reported by [com.hanfengruyue.pocketrdp.feature.session.render]'s
     * RdpSurface when it draws a newly-committed frame. This is the display-pipeline latency the
     * input→decode [controlLatencyMs] metric structurally cannot see. Passive: never invalidates,
     * never feeds the FPS counter (which stays sourced from content frames).
     */
    fun recordPresentLag(lagMs: Long) {
        if (lagMs < 0L || lagMs > PRESENT_LAG_MAX_MS) return
        synchronized(presentLagLock) {
            presentLagSamples[presentLagHead] = lagMs.toInt()
            presentLagHead = (presentLagHead + 1) % presentLagSamples.size
            if (presentLagCount < presentLagSamples.size) presentLagCount++
        }
    }

    /** Rolling MEDIAN decode→present delay (ms); -1 until the first sample. See [recordPresentLag]. */
    fun presentLagMs(): Int {
        synchronized(presentLagLock) {
            val n = presentLagCount
            if (n == 0) return -1
            val sorted = presentLagSamples.copyOf(n).also { it.sort() }
            return sorted[n / 2]
        }
    }

    /** One atomic, versioned view of TCP plus both possible RDP-UDP tunnels. */
    fun transportSnapshot(): RdpTransportSnapshot {
        val raw = withLiveInstance { inst -> LibFreeRDP.getTransportSnapshot(inst) }
        return decodeAndLogTransportSnapshot(raw, source = "metrics-poll", force = false)
    }

    fun sendClipboard(data: String) {
        withLiveInstance { inst -> LibFreeRDP.sendClipboardData(inst, data) }
    }

    /**
     * Push a DISPLAY_CONTROL_MONITOR_LAYOUT PDU through DRDYNVC's Display Control channel.
     *
     * Returns `false` if either the native instance is gone OR the disp channel hasn't been
     * negotiated yet — the latter happens for a few seconds after OnConnectionSuccess because
     * DRDYNVC sub-channels are brought up asynchronously. Callers (SessionViewModel) use this
     * return value to schedule retries until the server actually acknowledges with an
     * OnGraphicsResize.
     */
    fun sendMonitorLayout(width: Int, height: Int): Boolean {
        return withLiveInstance { inst ->
            LibFreeRDP.sendMonitorLayout(inst, width, height)
        } ?: false
    }

    fun hasH264(): Boolean = LibFreeRDP.hasH264Support()
    fun version(): String = LibFreeRDP.freerdp_get_version()

    /**
     * Approximate network latency (round-trip, ms) to the RDP host by timing a fresh TCP handshake
     * to host:port. This is a proxy for the true RDP-layer RTT (which FreeRDP's autodetect module
     * tracks internally but is not exposed through the current prebuilt JNI bridge — surfacing that
     * would need a native rebuild). Runs on the IO dispatcher; returns -1 on failure / no session.
     */
    suspend fun measureLatencyMs(): Int = withContext(Dispatchers.IO) {
        val host = lastHost ?: return@withContext -1
        if (!hasLiveInstance()) return@withContext -1
        runCatching {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, lastPort), LATENCY_PROBE_TIMEOUT_MS)
                ((System.nanoTime() - start) / NANOS_PER_MILLI).toInt().coerceIn(0, MAX_LATENCY_MS)
            }
        }.getOrDefault(-1)
    }

    private fun emit(event: RdpEvent) {
        check(eventChannel.trySend(event).isSuccess) { "RDP event channel unexpectedly closed" }
    }

    private fun isCurrentInstance(inst: Long): Boolean = nativeInstance == inst

    private fun hasLiveInstance(): Boolean = synchronized(nativeCallLock) {
        nativeInstance != 0L
    }

    private inline fun <T> withLiveInstance(block: (Long) -> T): T? {
        val inst = synchronized(nativeCallLock) {
            nativeInstance.takeIf { it != 0L }?.also { inFlightNativeCalls++ }
        } ?: return null
        return try {
            block(inst)
        } finally {
            releaseNativeCallLease()
        }
    }

    private inline fun <T> withSpecificLiveInstance(inst: Long, block: () -> T): T? {
        val acquired = synchronized(nativeCallLock) {
            if (nativeInstance == inst) {
                inFlightNativeCalls++
                true
            } else {
                false
            }
        }
        if (!acquired) return null
        return try {
            block()
        } finally {
            releaseNativeCallLease()
        }
    }

    private fun releaseNativeCallLease() {
        synchronized(nativeCallLock) {
            inFlightNativeCalls--
            check(inFlightNativeCalls >= 0)
            if (inFlightNativeCalls == 0) nativeCallLock.notifyAll()
        }
    }

    private fun resetTransportDiagnostics(requestedByApp: Boolean) {
        synchronized(transportDiagnosticLock) {
            multitransportRequestedForConnection = requestedByApp
            transportConnectedAtMs = 0L
            transportRequestObserved = false
            missingTransportRequestLogged = false
            incompatibleTransportSnapshotLogged = false
            lastLoggedTransportControl = null
            lastTransportTrafficLogMs = 0L
        }
    }

    private fun captureAndLogTransportSnapshot(
        inst: Long,
        source: String,
        force: Boolean,
    ): RdpTransportSnapshot = decodeAndLogTransportSnapshot(
        raw = LibFreeRDP.getTransportSnapshot(inst),
        source = source,
        force = force,
    )

    private fun decodeAndLogTransportSnapshot(
        raw: LongArray?,
        source: String,
        force: Boolean,
    ): RdpTransportSnapshot {
        val snapshot = RdpTransportSnapshot.decode(raw)
        val shouldTrack = synchronized(transportDiagnosticLock) {
            multitransportRequestedForConnection || snapshot.hasTransportEvidence()
        }
        if (!shouldTrack || raw == null) return snapshot

        if (!snapshot.knownVersion) {
            val shouldLog = synchronized(transportDiagnosticLock) {
                if (incompatibleTransportSnapshotLogged) {
                    false
                } else {
                    incompatibleTransportSnapshotLogged = true
                    true
                }
            }
            if (shouldLog) {
                val version = raw.firstOrNull()?.toString() ?: "missing"
                PocketLogger.w(
                    TAG,
                    "RDP-UDP diagnostic source=$source incompatible snapshot " +
                        "fieldCount=${raw.size} version=$version " +
                        "expectedFieldCount=${RdpTransportSnapshot.FIELD_COUNT} " +
                        "expectedVersion=${RdpTransportSnapshot.VERSION}; treating transport as TCP/unknown",
                )
            }
            return snapshot
        }

        logTransportSnapshot(snapshot, source, force)
        return snapshot
    }

    private fun logTransportSnapshot(
        snapshot: RdpTransportSnapshot,
        source: String,
        force: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val control = snapshot.controlDiagnostic()
        var logMissingRequest = false
        var logControlState = false
        var logTrafficHeartbeat = false
        synchronized(transportDiagnosticLock) {
            if (snapshot.hasTransportEvidence()) transportRequestObserved = true
            if (markMissingTransportRequestIfOverdue(now)) {
                logMissingRequest = true
            }

            if (force || control != lastLoggedTransportControl) {
                lastLoggedTransportControl = control
                lastTransportTrafficLogMs = now
                logControlState = true
            } else if (snapshot.udpActive &&
                now - lastTransportTrafficLogMs >= TRANSPORT_TRAFFIC_LOG_INTERVAL_MS
            ) {
                lastTransportTrafficLogMs = now
                logTrafficHeartbeat = true
            }
        }

        if (logMissingRequest) {
            PocketLogger.w(
                TAG,
                "RDP-UDP diagnostic: no server multitransport request was observed within " +
                    "${MULTITRANSPORT_REQUEST_WAIT_MS / MILLIS_PER_SECOND}s after TCP connected; " +
                    "the server or network path may not be offering RDP-UDP",
            )
        }

        if (logControlState) {
            val message =
                "RDP-UDP diagnostic source=$source $control; ${snapshot.trafficDiagnostic()}"
            val failed = snapshot.failure != RdpTransportFailure.NONE ||
                snapshot.phase == RdpTransportPhase.UDP_FAILED ||
                snapshot.phase == RdpTransportPhase.RECONNECTING_TCP ||
                snapshot.downgradedToTcp
            if (failed) PocketLogger.w(TAG, message) else PocketLogger.i(TAG, message)
        } else if (logTrafficHeartbeat) {
            PocketLogger.d(
                TAG,
                "RDP-UDP traffic heartbeat ${snapshot.trafficDiagnostic()}",
            )
        }
    }

    /** Must be called while [transportDiagnosticLock] is held. */
    private fun markMissingTransportRequestIfOverdue(now: Long): Boolean {
        if (!multitransportRequestedForConnection ||
            transportRequestObserved ||
            missingTransportRequestLogged
        ) {
            return false
        }
        if (transportConnectedAtMs <= 0L ||
            now - transportConnectedAtMs < MULTITRANSPORT_REQUEST_WAIT_MS
        ) {
            return false
        }
        missingTransportRequestLogged = true
        return true
    }

    private val eventListener = object : LibFreeRDP.EventListener {
        override fun OnPreConnect(inst: Long) {
            withSpecificLiveInstance(inst) {
                PocketLogger.d(TAG, "OnPreConnect inst=$inst")
            }
        }
        override fun OnConnectionSuccess(inst: Long) {
            withSpecificLiveInstance(inst) {
                certificatePromptOutstanding = false
                certificateFatalFailureOutstanding = false
                PocketLogger.i(TAG, "OnConnectionSuccess inst=$inst")
                synchronized(transportDiagnosticLock) {
                    transportConnectedAtMs = SystemClock.elapsedRealtime()
                }
                captureAndLogTransportSnapshot(inst, source = "connected", force = true)
                sessionRegistry.markConnected(this@RdpClient)
                emit(RdpEvent.Connected)
            }
        }
        override fun OnConnectionFailure(inst: Long) {
            val handled = withSpecificLiveInstance(inst) {
                captureAndLogTransportSnapshot(inst, source = "connection-failure", force = true)
                when (
                    certificateTerminalDisposition(
                        promptOutstanding = certificatePromptOutstanding,
                        fatalFailureOutstanding = certificateFatalFailureOutstanding,
                    )
                ) {
                    CertificateTerminalDisposition.PROMPT -> {
                        PocketLogger.i(TAG, "connection paused for certificate verification")
                        sessionRegistry.unregister(this@RdpClient)
                    }
                    CertificateTerminalDisposition.FATAL -> {
                        PocketLogger.e(TAG, "connection rejected because certificate data was invalid")
                        sessionRegistry.unregister(this@RdpClient)
                    }
                    CertificateTerminalDisposition.NONE -> {
                        val errorCode = LibFreeRDP.freerdp_get_last_error_code(inst)
                        val error = LibFreeRDP.freerdp_get_last_error_string(inst).orEmpty()
                        val retryable = isRetryableRdpFailure(errorCode)
                        PocketLogger.e(
                            TAG,
                            "OnConnectionFailure code=$errorCode last_error='$error'",
                        )
                        if (retryable) {
                            sessionRegistry.markReconnecting(this@RdpClient)
                        } else {
                            // Authentication/account failures will not heal by retrying and repeated
                            // attempts can lock the remote account. Remove the aggregate keep-alive
                            // registration and let SessionViewModel discard the decrypted params.
                            sessionRegistry.unregister(this@RdpClient)
                        }
                        emit(
                            RdpEvent.Failed(
                                "connection failure: ${error.ifBlank { "unknown error" }}",
                                retryable = retryable,
                            ),
                        )
                    }
                }
                true
            } == true
            // The callback lease must be released before teardown waits for all leases to drain.
            if (handled) closeNativeInstance(inst)
        }
        override fun OnDisconnecting(inst: Long) {
            withSpecificLiveInstance(inst) {
                PocketLogger.d(TAG, "OnDisconnecting inst=$inst")
            }
        }
        override fun OnDisconnected(inst: Long) {
            val handled = withSpecificLiveInstance(inst) {
                captureAndLogTransportSnapshot(inst, source = "disconnected", force = true)
                // A certificate callback can reject the connection before FreeRDP's main event
                // loop starts. Depending on the native exit status, that rejection can surface as
                // OnDisconnected rather than OnConnectionFailure. Preserve the certificate
                // decision in both terminal callbacks: a pending TOFU prompt must not auto-retry,
                // and a malformed/mismatched certificate must not be overwritten by a generic
                // disconnect that would enter the reconnect loop.
                when (
                    certificateTerminalDisposition(
                        promptOutstanding = certificatePromptOutstanding,
                        fatalFailureOutstanding = certificateFatalFailureOutstanding,
                    )
                ) {
                    CertificateTerminalDisposition.PROMPT -> {
                        PocketLogger.i(TAG, "connection paused for certificate verification")
                        sessionRegistry.unregister(this@RdpClient)
                    }
                    CertificateTerminalDisposition.FATAL -> {
                        PocketLogger.e(TAG, "connection rejected because certificate data was invalid")
                        sessionRegistry.unregister(this@RdpClient)
                    }
                    CertificateTerminalDisposition.NONE -> {
                        PocketLogger.i(TAG, "OnDisconnected")
                        sessionRegistry.markReconnecting(this@RdpClient)
                        emit(RdpEvent.Disconnected(reason = null))
                    }
                }
                true
            } == true
            if (handled) closeNativeInstance(inst)
        }
    }

    private val uiEventListener = object : LibFreeRDP.UIEventListener {
        override fun OnAuthenticate(inst: Long, username: StringBuilder, domain: StringBuilder, password: StringBuilder): Boolean {
            return withSpecificLiveInstance(inst) {
                emit(RdpEvent.CredentialsRequired)
                false
            } ?: false
        }

        override fun OnGatewayAuthenticate(inst: Long, username: StringBuilder, domain: StringBuilder, password: StringBuilder): Boolean = false

        override fun OnVerifyCertificateEx(inst: Long, host: String, port: Long, commonName: String, subject: String, issuer: String, fingerprint: String, flags: Long): Int {
            return withSpecificLiveInstance(inst) {
                if (!certificateEndpointMatches(lastHost, lastPort, host, port)) {
                    certificateFatalFailureOutstanding = true
                    emit(
                        RdpEvent.Failed(
                            "Server certificate was presented for an unexpected endpoint",
                            retryable = false,
                        ),
                    )
                    return@withSpecificLiveInstance 0
                }
                val sha256 = normalizeCertificateFingerprint(fingerprint)
                if (sha256.isEmpty()) {
                    certificateFatalFailureOutstanding = true
                    emit(RdpEvent.Failed("Server certificate fingerprint is invalid", retryable = false))
                    return@withSpecificLiveInstance 0
                }
                if (acceptedCertThumb != null && acceptedCertThumb.equals(sha256, ignoreCase = true)) {
                    1
                } else {
                    certificatePromptOutstanding = true
                    emit(
                        RdpEvent.CertificatePrompt(
                            host = host,
                            port = port.toInt(),
                            sha256 = sha256,
                            isChange = acceptedCertThumb != null,
                        ),
                    )
                    0
                }
            } ?: 0
        }

        override fun OnVerifyChangedCertificateEx(inst: Long, host: String, port: Long, commonName: String, subject: String, issuer: String, fingerprint: String, oldSubject: String, oldIssuer: String, oldFingerprint: String, flags: Long): Int {
            return withSpecificLiveInstance(inst) {
                if (!certificateEndpointMatches(lastHost, lastPort, host, port)) {
                    certificateFatalFailureOutstanding = true
                    emit(
                        RdpEvent.Failed(
                            "Changed server certificate was presented for an unexpected endpoint",
                            retryable = false,
                        ),
                    )
                    return@withSpecificLiveInstance 0
                }
                val sha256 = normalizeCertificateFingerprint(fingerprint)
                if (sha256.isEmpty()) {
                    certificateFatalFailureOutstanding = true
                    emit(RdpEvent.Failed("Changed server certificate fingerprint is invalid", retryable = false))
                    return@withSpecificLiveInstance 0
                }
                if (acceptedCertThumb != null && acceptedCertThumb.equals(sha256, ignoreCase = true)) {
                    1
                } else {
                    certificatePromptOutstanding = true
                    emit(RdpEvent.CertificatePrompt(host, port.toInt(), sha256, isChange = true))
                    0
                }
            } ?: 0
        }

        override fun OnGraphicsUpdate(inst: Long, x: Int, y: Int, w: Int, h: Int) {
            withSpecificLiveInstance(inst) {
            // End-to-end control-latency sampling: this frame is fully decoded (the gdi buffer is
            // ready before this callback). If an input is pending from an idle→action edge, the gap
            // to now is a genuine press→screen round-trip — feed it to the EMA. (See markDiscreteInput.)
            recordServerFrameForLatency()
            // Diagnostic heartbeat (throttled) — see gfxUpdateCount/lastGfxLogMs. Lets a bug report
            // tell a frozen picture (lines stop) from a live one (lines continue to disconnect).
            gfxUpdateCount++
            val gfxNow = SystemClock.uptimeMillis()
            if (gfxNow - lastGfxLogMs >= GFX_LOG_INTERVAL_MS) {
                lastGfxLogMs = gfxNow
                PocketLogger.d(TAG, "gfx alive: $gfxUpdateCount frames received (latest ${w}x$h at $x,$y)")
            }
            // Write this frame's dirty region into the BACK buffer (never the one the UI is drawing).
            val target = buffer.nativeBuffer() ?: return
            val copied = LibFreeRDP.updateGraphics(inst, target, x, y, w, h)
            if (!copied) return
            // The back buffer is one published generation behind — re-apply the region that changed
            // in the previous frame so the buffer we're about to publish is a complete mirror of the
            // gdi framebuffer (otherwise the swapped-in frame would be missing last frame's update).
            // BUT skip that second copy when this frame's rect already fully covers the stale region
            // (the common case for video / full-screen repaints) — re-copying it would just be a
            // redundant full-screen blit, the dominant per-frame cost behind the "卡顿". (issue #1)
            buffer.staleRect()?.let { r ->
                val updateRight = x.toLong() + w
                val updateBottom = y.toLong() + h
                if (!r.isEmpty &&
                    !(x <= r.left && y <= r.top &&
                        updateRight >= r.right.toLong() && updateBottom >= r.bottom.toLong())
                ) {
                    val staleCopied = LibFreeRDP.updateGraphics(
                        inst,
                        target,
                        r.left,
                        r.top,
                        r.width(),
                        r.height(),
                    )
                    if (!staleCopied) return@withSpecificLiveInstance
                }
            }
            // Swap back→front: the UI's free-running render loop now reads a complete, stable frame,
            // eliminating the mid-write tearing on large updates ("逐行扫描").
            buffer.commitFrame(x, y, w, h)
            _frameUpdates.tryEmit(Unit)
            }
        }

        override fun OnGraphicsResize(inst: Long, width: Int, height: Int, bpp: Int) {
            withSpecificLiveInstance(inst) {
                if (!isSafeFramebufferSize(width, height)) {
                    PocketLogger.e(TAG, "rejecting unsafe remote framebuffer dimensions ${width}x$height")
                    emit(
                        RdpEvent.Failed(
                            error = "Remote framebuffer dimensions are unsafe: ${width}x$height",
                            retryable = false,
                        ),
                    )
                    // This callback itself holds a native-call lease. Teardown must happen on a
                    // different thread so closeNativeInstance can wait for this lease to return.
                    closeNativeInstanceAfterCallback(inst)
                    return@withSpecificLiveInstance
                }
                PocketLogger.i(TAG, "OnGraphicsResize ${width}x$height bpp=$bpp")
                try {
                    buffer.resize(width, height)
                } catch (error: IllegalArgumentException) {
                    PocketLogger.e(TAG, "remote framebuffer allocation rejected: ${error.message}")
                    emit(RdpEvent.Failed("Remote framebuffer allocation failed", retryable = false))
                    closeNativeInstanceAfterCallback(inst)
                    return@withSpecificLiveInstance
                } catch (_: OutOfMemoryError) {
                    PocketLogger.e(TAG, "remote framebuffer allocation exhausted memory")
                    emit(RdpEvent.Failed("Remote framebuffer is too large for this device", retryable = false))
                    closeNativeInstanceAfterCallback(inst)
                    return@withSpecificLiveInstance
                }
                // A hostile or malfunctioning server can send resize PDUs faster than the UI can
                // consume them. Keep only the latest dimensions, and never queue Bitmap references
                // in the lossless lifecycle/security channel.
                _resizeUpdates.tryEmit(RdpResize(width, height))
            }
        }

        override fun OnRemoteClipboardChanged(inst: Long, data: String) {
            withSpecificLiveInstance(inst) {
                _clipboardUpdates.tryEmit(truncateUtf16Safely(data, MAX_CLIPBOARD_CHARS))
            }
        }

        override fun OnPointerSet(inst: Long, pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) {
            withSpecificLiveInstance(inst) {
                val requiredPixels = width.toLong() * height.toLong()
                if (width !in 1..MAX_CURSOR_DIMENSION ||
                    height !in 1..MAX_CURSOR_DIMENSION ||
                    requiredPixels > pixels.size.toLong()
                ) {
                    PocketLogger.w(TAG, "ignoring invalid remote cursor dimensions ${width}x$height")
                    return@withSpecificLiveInstance
                }
                val bitmap = runCatching {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.setPixels(pixels, 0, width, 0, 0, width, height)
                    }
                }.getOrElse {
                    PocketLogger.w(TAG, "ignoring remote cursor bitmap allocation failure")
                    return@withSpecificLiveInstance
                }
                _remoteCursor.value = RdpCursor.Image(
                    bitmap = bitmap,
                    hotX = hotX.coerceIn(0, width - 1),
                    hotY = hotY.coerceIn(0, height - 1),
                )
            }
        }

        override fun OnPointerSetNull(inst: Long) {
            withSpecificLiveInstance(inst) {
                _remoteCursor.value = RdpCursor.Hidden
            }
        }

        override fun OnPointerSetDefault(inst: Long) {
            withSpecificLiveInstance(inst) {
                _remoteCursor.value = RdpCursor.Default
            }
        }
    }

    init {
        if (LibFreeRDP.isNativeReady()) {
            PocketLogger.i(TAG, "FreeRDP native ready ver=${LibFreeRDP.freerdp_get_version()} h264=${LibFreeRDP.hasH264Support()}")
        } else {
            PocketLogger.w(TAG, "Native FreeRDP library not loaded; running in UI-stub mode")
            Log.w(TAG, "Native FreeRDP library not loaded; running in UI-stub mode")
        }
    }

    private fun buildCommandLine(p: RdpConnectionParams): Array<String> =
        buildRdpCommandLine(p, LibFreeRDP.hasH264Support())

    private fun closeNativeInstanceAfterCallback(inst: Long) {
        Thread {
            closeNativeInstance(inst)
            // Every caller emits a non-retryable framebuffer validation/allocation failure.
            // Keeping it as RECONNECTING would leave a ghost session and foreground service even
            // though SessionViewModel deliberately discards the reconnect parameters.
            sessionRegistry.unregister(this)
            buffer.release()
        }.apply {
            name = "freerdp-rejected-framebuffer"
            isDaemon = true
        }.start()
    }

    private fun redact(args: Array<String>): List<String> =
        args.map { arg ->
            when {
                arg.startsWith("/p:", ignoreCase = true) -> "/p:<redacted>"
                arg.startsWith("/u:", ignoreCase = true) -> "/u:<redacted>"
                arg.startsWith("/d:", ignoreCase = true) -> "/d:<redacted>"
                arg.startsWith("/v:", ignoreCase = true) -> "/v:<redacted>"
                arg.startsWith("/drive:", ignoreCase = true) -> "/drive:<redacted>"
                else -> arg
            }
        }

    companion object {
        private const val TAG = "RdpClient"
        // performanceFlags bit (mirrors ConnectionEntity.PERF_LOW_LATENCY_VISUALS) — use lean visuals.
        // core-rdp does not depend on core-data, so the value is duplicated here; keep the two in sync.
        const val PERF_LOW_LATENCY_VISUALS = 1
        // Per-connection cap on keyboard-input debug logs. Captures the start-of-typing
        // pattern (which is what we need to investigate "input → disconnect" timing) without
        // flooding logs when the user holds a key or pastes long text.
        private const val KEY_LOG_LIMIT = 50
        private const val DEFAULT_RDP_PORT = 3389
        private const val LATENCY_PROBE_TIMEOUT_MS = 2000
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_LATENCY_MS = 9999
        // End-to-end control-latency sampling tunables. Only arm a sample when the screen has been
        // idle this long (so the next frame is a genuine response, not one already streaming).
        private const val CONTROL_LATENCY_IDLE_GAP_MS = 80L
        // A press→frame gap beyond this is treated as "no genuine response" and discarded: the press
        // caused no visible change and we caught a later unrelated frame. Also bounds how stale a
        // pending arm may get. Tightened 600→420→300ms: with hardware (MediaCodec) decode the true felt
        // latency is well under 300ms, so a narrower window rejects more periodic-frame (cursor-blink)
        // mispairs for a truer reading, while still > a typical real response so genuine samples count.
        private const val CONTROL_LATENCY_RESPONSE_WINDOW_MS = 300L
        // Recent-sample ring + low percentile replace the old EMA-of-all (the terminal-blink inflation
        // fix). 20 samples ≈ tens of seconds of interaction; P25 = trustworthy low end without min-lock.
        private const val CONTROL_LATENCY_SAMPLE_WINDOW = 20
        private const val CONTROL_LATENCY_PERCENTILE = 0.25f
        // Decode→present (display-pipeline) latency estimator: rolling median over this many recent
        // drawn frames; samples above the cap are dropped as outliers (e.g. a frame held while the app
        // was backgrounded). ~30 frames ≈ 0.5–1 s of drawing at 30–60 fps.
        private const val PRESENT_LAG_SAMPLE_WINDOW = 30
        private const val PRESENT_LAG_MAX_MS = 1000L
        // How often the "gfx alive" diagnostic heartbeat is logged (frames keep arriving) — ~2 s.
        private const val GFX_LOG_INTERVAL_MS = 2000L
        // A server normally sends its multitransport request shortly after the TCP desktop becomes
        // active. Keep this long enough to avoid warning during ordinary channel startup, but short
        // enough that a single exported reproduction log clearly shows a missing request.
        private const val MULTITRANSPORT_REQUEST_WAIT_MS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000L
        // Active tunnel counters are useful evidence, but a 1 Hz line would rotate away the actual
        // negotiation. State transitions log immediately; steady traffic is sampled at this rate.
        private const val TRANSPORT_TRAFFIC_LOG_INTERVAL_MS = 30_000L
        private const val MAX_CLIPBOARD_CHARS = 256 * 1024
        private const val MAX_CURSOR_DIMENSION = 512
    }
}

internal fun normalizeCertificateFingerprint(value: String): String {
    val trimmed = value.trim()
    val payload = if (trimmed.startsWith("SHA256:", ignoreCase = true)) {
        trimmed.substringAfter(':')
    } else {
        trimmed
    }
    if (payload.any { !it.isWhitespace() && it != ':' && it.digitToIntOrNull(16) == null }) {
        return ""
    }
    return payload
        .filterNot { it.isWhitespace() || it == ':' }
        .lowercase(Locale.ROOT)
        .takeIf { it.length == SHA256_FINGERPRINT_HEX_LENGTH }
        .orEmpty()
}

internal fun certificateEndpointMatches(
    expectedHost: String?,
    expectedPort: Int,
    actualHost: String,
    actualPort: Long,
): Boolean =
    !expectedHost.isNullOrBlank() &&
        canonicalCertificateHost(expectedHost) == canonicalCertificateHost(actualHost) &&
        actualPort == expectedPort.toLong()

private fun canonicalCertificateHost(host: String): String =
    host.trim()
        .removeSurrounding("[", "]")
        .trimEnd('.')
        .lowercase(Locale.ROOT)

private const val SHA256_FINGERPRINT_HEX_LENGTH = 64

internal fun isSafeFramebufferSize(width: Int, height: Int): Boolean =
    width in 1..MAX_FRAMEBUFFER_DIMENSION &&
        height in 1..MAX_FRAMEBUFFER_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_FRAMEBUFFER_PIXELS

/**
 * Whether an unsuccessful FreeRDP connection should enter PocketRDP's exponential reconnect loop.
 *
 * Connection-class authentication, credential and account-policy errors are permanent until the
 * user or administrator changes something. Retrying those errors can trigger an account lockout.
 * Network, KDC, activation-timeout and target-booting failures remain retryable.
 */
internal fun isRetryableRdpFailure(errorCode: Long): Boolean {
    val errorClass = ((errorCode ushr FREERDP_ERROR_CLASS_SHIFT) and FREERDP_ERROR_FIELD_MASK).toInt()
    if (errorClass != FREERDP_CONNECT_ERROR_CLASS) return true
    val errorType = (errorCode and FREERDP_ERROR_FIELD_MASK).toInt()
    return errorType !in AUTH_FAILURE_FIRST..AUTH_FAILURE_LAST &&
        errorType !in EXPIRED_OR_REVOKED_FIRST..EXPIRED_OR_REVOKED_LAST &&
        errorType !in ACCOUNT_POLICY_FAILURE_FIRST..ACCOUNT_POLICY_FAILURE_LAST
}

internal enum class CertificateTerminalDisposition {
    NONE,
    PROMPT,
    FATAL,
}

/**
 * Certificate rejection can terminate through either native terminal callback. A fatal validation
 * error wins if multiple certificate callbacks occurred during one connection (for example gateway
 * plus target), so an earlier TOFU prompt can never downgrade a later endpoint/fingerprint failure.
 */
internal fun certificateTerminalDisposition(
    promptOutstanding: Boolean,
    fatalFailureOutstanding: Boolean,
): CertificateTerminalDisposition = when {
    fatalFailureOutstanding -> CertificateTerminalDisposition.FATAL
    promptOutstanding -> CertificateTerminalDisposition.PROMPT
    else -> CertificateTerminalDisposition.NONE
}

internal fun truncateUtf16Safely(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    if (maxChars <= 0) return ""
    var end = maxChars
    if (end < value.length &&
        Character.isHighSurrogate(value[end - 1]) &&
        Character.isLowSurrogate(value[end])
    ) {
        end--
    }
    return value.substring(0, end)
}

private const val MAX_FRAMEBUFFER_DIMENSION = 8192
private const val MAX_FRAMEBUFFER_PIXELS = 16_777_216L
private const val FREERDP_CONNECT_ERROR_CLASS = 2
private const val FREERDP_ERROR_CLASS_SHIFT = 16
private const val FREERDP_ERROR_FIELD_MASK = 0xffffL
private const val AUTH_FAILURE_FIRST = 0x09
private const val AUTH_FAILURE_LAST = 0x0B
private const val EXPIRED_OR_REVOKED_FIRST = 0x0E
private const val EXPIRED_OR_REVOKED_LAST = 0x10
private const val ACCOUNT_POLICY_FAILURE_FIRST = 0x12
private const val ACCOUNT_POLICY_FAILURE_LAST = 0x1B
