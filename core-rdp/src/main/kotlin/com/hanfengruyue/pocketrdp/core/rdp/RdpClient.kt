package com.hanfengruyue.pocketrdp.core.rdp

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
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
    private var closingNativeInstanceWaiters: Int = 0
    @Volatile private var nativeInstance: Long = 0L
    private val rejectedNativeInstance = AtomicLong(0L)
    @Volatile private var acceptedCertThumb: String? = null
    @Volatile private var certificatePromptOutstanding: Boolean = false
    @Volatile private var certificateFatalFailureOutstanding: Boolean = false
    private val credentialPromptGate = CredentialPromptGate()
    @Volatile private var credentialsCancelled: Boolean = false
    @Volatile private var autoReconnectEnabled: Boolean = true
    private val transportTracker = RdpTransportTracker(logTag = TAG)

    // Host/port of the active session — used by [measureLatencyMs] for the latency probe.
    @Volatile private var lastHost: String? = null
    @Volatile private var lastPort: Int = DEFAULT_RDP_PORT

    // End-to-end control latency (discrete press → decoded server frame) and display-pipeline lag.
    private val controlLatency = ControlLatencyTracker()
    private val presentLag = PresentLagTracker()

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
        val effectiveMultitransport = transportTracker.effectiveMultitransport(params.useMultitransport)
        val effectiveParams = if (effectiveMultitransport == params.useMultitransport) {
            params
        } else {
            params.copy(useMultitransport = false)
        }
        autoReconnectEnabled = effectiveParams.autoReconnect
        PocketLogger.i(
            TAG,
            "connect() endpoint=<redacted> h264=${effectiveParams.useH264} gfx=${effectiveParams.useGfx} " +
                "dynRes=${effectiveParams.dynamicResolution} " +
                "multitransport=${effectiveParams.useMultitransport} " +
                "autoReconnect=${effectiveParams.autoReconnect}",
        )
        logNativeReadinessOnce()
        if (!LibFreeRDP.isNativeReady()) {
            PocketLogger.e(TAG, "native FreeRDP not ready — refusing connect")
            emit(
                RdpEvent.Failed(
                    "native FreeRDP not built — see core-rdp/build.gradle.kts to enable CMake superbuild",
                    retryable = false,
                    udpDowngraded = transportTracker.hasObservedDowngrade(),
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
        credentialPromptGate.cancel()
        credentialsCancelled = false
        _remoteCursor.value = RdpCursor.Default

        transportTracker.reset(effectiveParams.useMultitransport)
        if (effectiveParams.useMultitransport) {
            PocketLogger.i(
                TAG,
                "RDP-UDP diagnostic: enabled for this attempt; waiting for a server multitransport request",
            )
        } else {
            PocketLogger.i(TAG, "RDP-UDP diagnostic: disabled for this attempt; TCP-only requested")
        }

        emit(RdpEvent.Connecting)
        acceptedCertThumb = effectiveParams.acceptedCertThumbprint
            ?.let(::normalizeCertificateFingerprint)
            ?.takeIf(String::isNotEmpty)
        certificatePromptOutstanding = false
        certificateFatalFailureOutstanding = false
        lastHost = effectiveParams.host
        lastPort = effectiveParams.port
        val inst = LibFreeRDP.newInstance(context)
        if (inst == 0L) {
            PocketLogger.e(TAG, "LibFreeRDP.newInstance returned 0")
            emit(
                RdpEvent.Failed(
                    "freerdp_new returned 0",
                    retryable = false,
                    udpDowngraded = transportTracker.hasObservedDowngrade(),
                ),
            )
            return
        }
        synchronized(nativeCallLock) {
            rejectedNativeInstance.set(0L)
            nativeInstance = inst
        }
        sessionRegistry.markConnecting(this, effectiveParams)
        LibFreeRDP.registerEventListener(inst, eventListener)
        LibFreeRDP.registerUIEventListener(inst, uiEventListener)
        keyEventLogCount = 0
        unicodeEventLogCount = 0
        controlLatency.reset()
        presentLag.reset()
        gfxUpdateCount = 0
        lastGfxLogMs = 0L
        PocketLogger.d(TAG, "FreeRDP native instance allocated")

        val args = buildCommandLine(effectiveParams)
        PocketLogger.i(TAG, "args=${redact(args).joinToString(" ")}")
        val parsed = withSpecificLiveInstance(inst) {
            LibFreeRDP.setConnectionArgs(inst, args)
        } ?: false
        if (!parsed) {
            val err = withSpecificLiveInstance(inst) {
                LibFreeRDP.freerdp_get_last_error_string(inst)
            }.orEmpty()
            PocketLogger.e(TAG, "freerdp_parse_arguments failed (last_error='$err')")
            emit(
                RdpEvent.Failed(
                    "freerdp_parse_arguments failed: $err",
                    retryable = false,
                    udpDowngraded = transportTracker.hasObservedDowngrade(),
                ),
            )
            sessionRegistry.unregister(this)
            closeNativeInstance(inst)
            return
        }

        // JNI connect starts FreeRDP's native worker and returns. Keep the short startup call away
        // from the UI thread; the native worker's lifetime is joined by freerdp_free().
        Thread({
            PocketLogger.d(TAG, "starting FreeRDP native worker")
            val ok = withSpecificLiveInstance(inst) { LibFreeRDP.connect(inst) } ?: false
            if (!ok) {
                handleConnectReturnFailure(inst)
            }
        }, "freerdp-connect-start").apply {
            isDaemon = true
            start()
        }
    }

    private fun logNativeReadinessOnce() {
        if (!nativeReadinessLogged.compareAndSet(false, true)) return
        if (LibFreeRDP.isNativeReady()) {
            PocketLogger.i(
                TAG,
                "FreeRDP native ready ver=${LibFreeRDP.freerdp_get_version()} " +
                    "h264=${LibFreeRDP.hasH264Support()}",
            )
        } else {
            PocketLogger.w(TAG, "Native FreeRDP library not loaded; running in UI-stub mode")
        }
    }

    private fun handleConnectReturnFailure(inst: Long) {
        val failure = withSpecificLiveInstance(inst) {
            if (rejectedNativeInstance.get() == inst) {
                null
            } else {
                captureAndLogTransportSnapshot(inst, source = "connect-return", force = true)
                val udpDowngraded = transportTracker.hasObservedDowngrade()
                Triple(
                    LibFreeRDP.freerdp_get_last_error_code(inst),
                    LibFreeRDP.freerdp_get_last_error_string(inst).orEmpty(),
                    udpDowngraded,
                )
            }
        }
        if (failure == null || !isCurrentInstance(inst) || rejectedNativeInstance.get() == inst) {
            PocketLogger.d(TAG, "stale freerdp_connect return ignored")
            return
        }
        val (errorCode, err, udpDowngraded) = failure
        _remoteCursor.value = RdpCursor.Default
        when (
            certificateTerminalDisposition(
                promptOutstanding = certificatePromptOutstanding,
                fatalFailureOutstanding = certificateFatalFailureOutstanding,
            )
        ) {
            CertificateTerminalDisposition.PROMPT -> {
                PocketLogger.i(TAG, "connection paused for certificate verification")
                // There is no native connection or scheduled retry while the user decides.
                // Keeping this client registered as RECONNECTING would leave the aggregate FGS
                // and its CPU/Wi-Fi locks alive indefinitely.
                sessionRegistry.unregister(this)
                closeNativeInstance(inst)
                return
            }
            CertificateTerminalDisposition.FATAL -> {
                PocketLogger.e(TAG, "connection rejected because certificate data was invalid")
                sessionRegistry.unregister(this)
                closeNativeInstance(inst)
                return
            }
            CertificateTerminalDisposition.NONE -> Unit
        }
        val retryable = shouldAutoReconnectRdpFailure(
            autoReconnectEnabled = autoReconnectEnabled,
            credentialsCancelled = credentialsCancelled,
            errorCode = errorCode,
        )
        PocketLogger.e(
            TAG,
            "freerdp_connect returned false (code=$errorCode, retryable=$retryable, " +
                "udpDowngraded=$udpDowngraded, last_error='$err')",
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
                udpDowngraded = udpDowngraded,
            ),
        )
        closeNativeInstance(inst)
    }

    fun submitCredentials(username: String, domain: String, password: String) {
        credentialPromptGate.submit(username, domain, password)
    }

    fun cancelCredentials() {
        credentialPromptGate.cancel()
    }

    fun hasObservedUdpDowngrade(): Boolean = transportTracker.hasObservedDowngrade()

    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
    }

    fun clearReconnectingSession(): Boolean = sessionRegistry.unregisterIfReconnecting(this)

    fun disconnect() {
        credentialPromptGate.cancel()
        _remoteCursor.value = RdpCursor.Default
        val inst = synchronized(nativeCallLock) { nativeInstance }
        if (inst == 0L) {
            if (sessionRegistry.contains(this)) {
                sessionRegistry.unregister(this)
                emit(
                    RdpEvent.Disconnected(
                        reason = "user",
                        retryable = false,
                        udpDowngraded = transportTracker.hasObservedDowngrade(),
                    ),
                )
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
        emit(
            RdpEvent.Disconnected(
                reason = "user",
                retryable = false,
                udpDowngraded = transportTracker.hasObservedDowngrade(),
            ),
        )
    }

    fun hasActiveSession(): Boolean = sessionRegistry.contains(this)

    fun runIfNoActiveSessions(action: () -> Unit): Boolean =
        sessionRegistry.runIfNoActiveSessions(action)

    private fun closeNativeInstance(inst: Long): Boolean {
        val detached = synchronized(nativeCallLock) {
            if (nativeInstance != inst) return@synchronized false
            nativeInstance = 0L
            credentialPromptGate.cancel(inst)
            waitForInFlightNativeCallsLocked()
            true
        }
        if (!detached) return false
        LibFreeRDP.unregisterEventListener(inst)
        LibFreeRDP.unregisterUIEventListener(inst)
        Thread {
            // No new JNI call can acquire a lease after detach, and all earlier leases drained
            // above. Do not hold nativeCallLock while freeInstance disconnects and joins the
            // native worker: a callback already in flight may still observe the detached state.
            LibFreeRDP.freeInstance(inst)
        }.apply {
            name = "freerdp-cleanup"
            isDaemon = true
        }.start()
        return true
    }

    private fun waitForInFlightNativeCallsLocked() {
        if (inFlightNativeCalls <= 0) return
        closingNativeInstanceWaiters++
        try {
            while (inFlightNativeCalls > 0) {
                nativeCallLock.wait()
            }
        } finally {
            closingNativeInstanceWaiters--
        }
    }

    fun sendKeyEvent(scanCode: Int, down: Boolean) {
        val forwarded = withLiveInstance { inst ->
            if (keyEventLogCount < KEY_LOG_LIMIT) {
                keyEventLogCount++
                PocketLogger.d(TAG, "keyboard event forwarded (#$keyEventLogCount)")
            }
            if (down) markDiscreteInput()
            LibFreeRDP.sendKeyEvent(inst, scanCode, down)
            Unit
        } != null
        if (!forwarded) {
            // Caller is sending input after disconnect — silently drop. This used to push to
            // a freed native instance.
            PocketLogger.w(TAG, "keyboard event dropped (no native instance)")
        }
    }

    fun sendUnicodeKey(codePoint: Int, down: Boolean) {
        val forwarded = withLiveInstance { inst ->
            if (unicodeEventLogCount < KEY_LOG_LIMIT) {
                unicodeEventLogCount++
                PocketLogger.d(TAG, "unicode keyboard event forwarded (#$unicodeEventLogCount)")
            }
            if (down) markDiscreteInput()
            LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, down)
            Unit
        } != null
        if (!forwarded) {
            PocketLogger.w(TAG, "unicode keyboard event dropped (no native instance)")
        }
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
        withLiveInstance { inst ->
            // Latency timing: arm ONLY on a button PRESS (click / drag-start). A plain cursor MOVE is
            // acked by the server via a pointer PDU (hardware cursor), NOT a framebuffer update — so
            // timing move→OnGraphicsUpdate paired the move with a much-later unrelated frame (clock
            // tick, popup) and grossly inflated the reading (field bug: 操控延迟 484ms on a 0ms-RTT
            // local host). A press reliably triggers a visible response we can actually time.
            val isButtonPress = flags and RdpPointerFlags.DOWN != 0 &&
                flags and (RdpPointerFlags.BUTTON1 or RdpPointerFlags.BUTTON2 or RdpPointerFlags.BUTTON3) != 0
            if (isButtonPress) markDiscreteInput()
            LibFreeRDP.sendCursorEvent(inst, x, y, flags)
        }
    }

    /**
     * Forward a native multi-touch contact (issue: 调用 Windows 原生触屏). [action] is one of
     * [RdpTouchAction] (DOWN/MOVE/UP); [contactId] is the finger id tracked across the gesture.
     * Silently dropped (returns) when there's no live instance or the rdpei channel isn't up yet —
     * never tears down the session.
     */
    fun sendTouch(contactId: Int, x: Int, y: Int, action: Int) {
        withLiveInstance { inst ->
            // Same as cursor: arm latency only on a touch DOWN (the press), not MOVE/UP.
            if (action == RdpTouchAction.DOWN) markDiscreteInput()
            LibFreeRDP.sendTouch(inst, contactId, x, y, action)
        }
    }

    /**
     * Arm an end-to-end latency sample for a DISCRETE input (click / key / touch press — NOT a cursor
     * move; see [sendCursorEvent]).
     */
    private fun markDiscreteInput() {
        controlLatency.markDiscreteInput(SystemClock.uptimeMillis())
    }

    /**
     * Smoothed end-to-end control latency (ms): input → on-screen frame, empirically including
     * server processing, encode, network and client decode. -1 until the first sample (or no live
     * session). This is the latency the user actually feels — far higher than [measureLatencyMs]'s
     * raw network RTT.
     */
    fun controlLatencyMs(): Int {
        if (!hasLiveInstance()) return -1
        return controlLatency.estimateMs()
    }

    /** Accepted-sample count (a discrete input that got a timely frame) — METRIC-4 diagnostic. */
    fun latencyAccepted(): Int = controlLatency.accepted()

    /** Discarded-sample count (a discrete input with no frame within the window) — METRIC-4 diagnostic. */
    fun latencyDiscarded(): Int = controlLatency.discarded()

    /**
     * Record one decode→present delay (ms) reported by [com.hanfengruyue.pocketrdp.feature.session.render]'s
     * RdpSurface when it draws a newly-committed frame. This is the display-pipeline latency the
     * input→decode [controlLatencyMs] metric structurally cannot see. Passive: never invalidates,
     * never feeds the FPS counter (which stays sourced from content frames).
     */
    fun recordPresentLag(lagMs: Long) {
        presentLag.record(lagMs)
    }

    /** Rolling MEDIAN decode→present delay (ms); -1 until the first sample. See [recordPresentLag]. */
    fun presentLagMs(): Int = presentLag.medianMs()

    /** One atomic, versioned view of TCP plus both possible RDP-UDP tunnels. */
    fun transportSnapshot(): RdpTransportSnapshot {
        val raw = withLiveInstance { inst -> LibFreeRDP.getTransportSnapshot(inst) }
        return transportTracker.decodeAndLog(raw, source = "metrics-poll", force = false)
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
            val address = InetSocketAddress(host, lastPort)
            if (address.isUnresolved) return@runCatching -1
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(address, LATENCY_PROBE_TIMEOUT_MS)
                ((System.nanoTime() - start) / NANOS_PER_MILLI).toInt().coerceIn(0, MAX_LATENCY_MS)
            }
        }.getOrDefault(-1)
    }

    private fun emit(event: RdpEvent) {
        check(eventChannel.trySend(event).isSuccess) { "RDP event channel unexpectedly closed" }
    }

    private fun isCurrentInstance(inst: Long): Boolean = nativeInstance == inst

    private fun hasLiveInstance(): Boolean = nativeInstance != 0L

    private inline fun <T> withLiveInstance(block: (Long) -> T): T? {
        if (nativeInstance == 0L) return null
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
            if (inFlightNativeCalls == 0 && closingNativeInstanceWaiters > 0) {
                nativeCallLock.notifyAll()
            }
        }
    }

    private fun captureAndLogTransportSnapshot(
        inst: Long,
        source: String,
        force: Boolean,
    ): RdpTransportSnapshot = transportTracker.decodeAndLog(
        raw = LibFreeRDP.getTransportSnapshot(inst),
        source = source,
        force = force,
    )

    private val eventListener = object : LibFreeRDP.EventListener {
        override fun OnPreConnect(inst: Long) {
            withSpecificLiveInstance(inst) {
                PocketLogger.d(TAG, "OnPreConnect inst=$inst")
            }
        }
        override fun OnConnectionSuccess(inst: Long) {
            if (rejectedNativeInstance.get() == inst) {
                PocketLogger.d(TAG, "ignoring OnConnectionSuccess for rejected framebuffer instance")
                return
            }
            withSpecificLiveInstance(inst) {
                certificatePromptOutstanding = false
                certificateFatalFailureOutstanding = false
                PocketLogger.i(TAG, "OnConnectionSuccess inst=$inst")
                transportTracker.markConnected()
                captureAndLogTransportSnapshot(inst, source = "connected", force = true)
                sessionRegistry.markConnected(this@RdpClient)
                emit(RdpEvent.Connected)
            }
        }
        override fun OnConnectionFailure(inst: Long) {
            if (rejectedNativeInstance.get() == inst) {
                PocketLogger.d(TAG, "ignoring OnConnectionFailure for rejected framebuffer instance")
                return
            }
            val handled = withSpecificLiveInstance(inst) {
                captureAndLogTransportSnapshot(inst, source = "connection-failure", force = true)
                val udpDowngraded = transportTracker.hasObservedDowngrade()
                _remoteCursor.value = RdpCursor.Default
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
                        val retryable = shouldAutoReconnectRdpFailure(
                            autoReconnectEnabled = autoReconnectEnabled,
                            credentialsCancelled = credentialsCancelled,
                            errorCode = errorCode,
                        )
                        PocketLogger.e(
                            TAG,
                            "OnConnectionFailure code=$errorCode retryable=$retryable " +
                                "udpDowngraded=$udpDowngraded last_error='$error'",
                        )
                        if (retryable) {
                            sessionRegistry.markReconnecting(this@RdpClient)
                        } else {
                            // Authentication/account failures will not heal by retrying and
                            // repeated attempts can lock the remote account. Remove the
                            // aggregate keep-alive registration and discard decrypted params.
                            sessionRegistry.unregister(this@RdpClient)
                        }
                        emit(
                            RdpEvent.Failed(
                                "connection failure: ${error.ifBlank { "unknown error" }}",
                                retryable = retryable,
                                udpDowngraded = udpDowngraded,
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
            if (rejectedNativeInstance.get() == inst) {
                PocketLogger.d(TAG, "ignoring OnDisconnected for rejected framebuffer instance")
                return
            }
            val handled = withSpecificLiveInstance(inst) {
                captureAndLogTransportSnapshot(inst, source = "disconnected", force = true)
                val udpDowngraded = transportTracker.hasObservedDowngrade()
                val errorCode = LibFreeRDP.freerdp_get_last_error_code(inst)
                val error = LibFreeRDP.freerdp_get_last_error_string(inst).orEmpty()
                _remoteCursor.value = RdpCursor.Default
                // A certificate callback can reject the connection before FreeRDP's main event
                // loop starts. Depending on the native exit status, that rejection can surface
                // as OnDisconnected rather than OnConnectionFailure. Preserve the certificate
                // decision in both terminal callbacks.
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
                        val retryable = shouldAutoReconnectRdpFailure(
                            autoReconnectEnabled = autoReconnectEnabled,
                            credentialsCancelled = credentialsCancelled,
                            errorCode = errorCode,
                        )
                        val reason = disconnectReasonForError(
                            errorCode = errorCode,
                            errorString = error,
                            retryable = retryable,
                        )
                        PocketLogger.i(
                            TAG,
                            "OnDisconnected code=$errorCode retryable=$retryable " +
                                "udpDowngraded=$udpDowngraded",
                        )
                        if (retryable) {
                            sessionRegistry.markReconnecting(this@RdpClient)
                        } else {
                            sessionRegistry.unregister(this@RdpClient)
                        }
                        emit(
                            RdpEvent.Disconnected(
                                reason = reason,
                                retryable = retryable,
                                udpDowngraded = udpDowngraded,
                            ),
                        )
                    }
                }
                true
            } == true
            if (handled) closeNativeInstance(inst)
        }
    }

    private val uiEventListener = object : LibFreeRDP.UIEventListener {
        override fun OnAuthenticate(
            inst: Long,
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = handleAuthenticate(inst, username, domain, password)

        override fun OnGatewayAuthenticate(
            inst: Long,
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = false

        override fun OnVerifyCertificateEx(
            inst: Long,
            host: String,
            port: Long,
            commonName: String,
            subject: String,
            issuer: String,
            fingerprint: String,
            flags: Long,
        ): Int = verifyCertificate(inst, host, port, fingerprint, changed = false)

        override fun OnVerifyChangedCertificateEx(
            inst: Long,
            host: String,
            port: Long,
            commonName: String,
            subject: String,
            issuer: String,
            fingerprint: String,
            oldSubject: String,
            oldIssuer: String,
            oldFingerprint: String,
            flags: Long,
        ): Int = verifyCertificate(inst, host, port, fingerprint, changed = true)

        override fun OnGraphicsUpdate(inst: Long, x: Int, y: Int, w: Int, h: Int) {
            if (rejectedNativeInstance.get() == inst) return
            withSpecificLiveInstance(inst) {
                val nowMs = SystemClock.uptimeMillis()
                // End-to-end control-latency sampling: this frame is fully decoded (the gdi buffer is
                // ready before this callback). If an input is pending from an idle→action edge, the gap
                // to now is a genuine press→screen round-trip. (See markDiscreteInput.)
                controlLatency.recordServerFrame(nowMs)
                // Diagnostic heartbeat (throttled) — see gfxUpdateCount/lastGfxLogMs. Lets a bug report
                // tell a frozen picture (lines stop) from a live one (lines continue to disconnect).
                gfxUpdateCount++
                if (nowMs - lastGfxLogMs >= GFX_LOG_INTERVAL_MS) {
                    lastGfxLogMs = nowMs
                    PocketLogger.d(TAG, "gfx alive: $gfxUpdateCount frames received (latest ${w}x$h at $x,$y)")
                }
                // Copy this frame's dirty region (plus previous stale rect if uncovered) into the
                // BACK buffer, then swap back→front so the UI reads a complete, stable frame.
                val updated = buffer.updateBackBuffer(
                    inst = inst,
                    x = x,
                    y = y,
                    w = w,
                    h = h,
                    commitTimeMs = nowMs,
                    copyRegion = LibFreeRDP::updateGraphics,
                )
                if (updated) {
                    _frameUpdates.tryEmit(Unit)
                }
            }
        }

        override fun OnGraphicsResize(inst: Long, width: Int, height: Int, bpp: Int) {
            if (rejectedNativeInstance.get() == inst) return
            withSpecificLiveInstance(inst) {
                if (!isSafeFramebufferSize(width, height)) {
                    rejectFramebuffer(
                        inst = inst,
                        logMessage = "rejecting unsafe remote framebuffer dimensions ${width}x$height",
                        eventMessage = "Remote framebuffer dimensions are unsafe: ${width}x$height",
                    )
                    return@withSpecificLiveInstance
                }
                PocketLogger.i(TAG, "OnGraphicsResize ${width}x$height bpp=$bpp")
                try {
                    buffer.resize(width, height)
                } catch (error: IllegalArgumentException) {
                    rejectFramebuffer(
                        inst = inst,
                        logMessage = "remote framebuffer allocation rejected: ${error.message}",
                        eventMessage = "Remote framebuffer allocation failed",
                    )
                    return@withSpecificLiveInstance
                } catch (_: OutOfMemoryError) {
                    rejectFramebuffer(
                        inst = inst,
                        logMessage = "remote framebuffer allocation exhausted memory",
                        eventMessage = "Remote framebuffer is too large for this device",
                    )
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
                    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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

    private fun verifyCertificate(
        inst: Long,
        host: String,
        port: Long,
        fingerprint: String,
        changed: Boolean,
    ): Int = withSpecificLiveInstance(inst) {
        val label = if (changed) "Changed server certificate" else "Server certificate"
        if (!certificateEndpointMatches(lastHost, lastPort, host, port)) {
            certificateFatalFailureOutstanding = true
            emit(RdpEvent.Failed("$label was presented for an unexpected endpoint", retryable = false))
            return@withSpecificLiveInstance 0
        }
        val sha256 = normalizeCertificateFingerprint(fingerprint)
        if (sha256.isEmpty()) {
            certificateFatalFailureOutstanding = true
            emit(RdpEvent.Failed("$label fingerprint is invalid", retryable = false))
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
                    isChange = changed || acceptedCertThumb != null,
                ),
            )
            0
        }
    } ?: 0

    private fun buildCommandLine(p: RdpConnectionParams): Array<String> =
        buildRdpCommandLine(p, LibFreeRDP.hasH264Support())

    private fun handleAuthenticate(
        inst: Long,
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        val deferred = synchronized(nativeCallLock) {
            if (nativeInstance != inst || rejectedNativeInstance.get() == inst) {
                return false
            }
            val next = credentialPromptGate.begin(inst)
            emit(
                RdpEvent.CredentialsRequired(
                    host = lastHost.orEmpty(),
                    initialUsername = username.toString(),
                    initialDomain = domain.toString(),
                ),
            )
            next
        }
        PocketLogger.i(TAG, "interactive credentials requested")
        val submitted = credentialPromptGate.awaitResponse(inst, deferred)
        if (submitted == null) {
            if (isCurrentInstance(inst) && rejectedNativeInstance.get() != inst) {
                credentialsCancelled = true
            }
            return false
        }
        val accepted = isCurrentInstance(inst) && rejectedNativeInstance.get() != inst
        if (accepted) {
            applyCredentials(submitted, username, domain, password)
        }
        return accepted
    }

    private fun rejectFramebuffer(inst: Long, logMessage: String, eventMessage: String) {
        rejectedNativeInstance.set(inst)
        PocketLogger.e(TAG, logMessage)
        emit(
            RdpEvent.Failed(
                error = eventMessage,
                retryable = false,
                udpDowngraded = transportTracker.hasObservedDowngrade(),
            ),
        )
        // This callback itself holds a native-call lease. Teardown must happen on a different
        // thread so closeNativeInstance can wait for this lease to return.
        closeNativeInstanceAfterCallback(inst)
    }

    private fun closeNativeInstanceAfterCallback(inst: Long) {
        Thread {
            if (closeNativeInstance(inst)) {
                // Every caller emits a non-retryable framebuffer validation/allocation failure.
                // Keeping it as RECONNECTING would leave a ghost session and foreground service
                // even though SessionViewModel deliberately discards the reconnect parameters.
                sessionRegistry.unregister(this)
                buffer.release()
            }
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
        private val nativeReadinessLogged = AtomicBoolean(false)
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
        // How often the "gfx alive" diagnostic heartbeat is logged (frames keep arriving) — ~2 s.
        private const val GFX_LOG_INTERVAL_MS = 2000L

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
    val errorType = (errorCode and FREERDP_ERROR_FIELD_MASK).toInt()
    return when (errorClass) {
        FREERDP_ERRINFO_ERROR_CLASS -> errorType !in NON_RETRYABLE_ERRINFO_TYPES
        FREERDP_CONNECT_ERROR_CLASS -> errorType !in NON_RETRYABLE_CONNECT_TYPES
        else -> true
    }
}

internal fun shouldAutoReconnectRdpFailure(
    autoReconnectEnabled: Boolean,
    credentialsCancelled: Boolean,
    errorCode: Long,
): Boolean = autoReconnectEnabled && !credentialsCancelled && isRetryableRdpFailure(errorCode)

/**
 * Resolve a user-visible disconnect reason from FreeRDP's last error state.
 *
 * When a link drops with errorCode == 0 (ERRBASE_SUCCESS), FreeRDP maps code 0 to "Success.".
 * Suppress that placeholder so the UI renders its localized generic disconnect label instead.
 */
internal fun disconnectReasonForError(
    errorCode: Long,
    errorString: String,
    retryable: Boolean,
): String? = when {
    retryable -> null
    errorCode == 0L -> null
    else -> errorString.trim().takeIf { it.isNotEmpty() }
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
private const val FREERDP_ERRINFO_ERROR_CLASS = 1
private const val FREERDP_CONNECT_ERROR_CLASS = 2
private const val FREERDP_ERROR_CLASS_SHIFT = 16
private const val FREERDP_ERROR_FIELD_MASK = 0xffffL
private const val AUTH_FAILURE_FIRST = 0x09
private const val AUTH_FAILURE_LAST = 0x0B
private const val EXPIRED_OR_REVOKED_FIRST = 0x0E
private const val EXPIRED_OR_REVOKED_LAST = 0x10
private const val ACCOUNT_POLICY_FAILURE_FIRST = 0x12
private const val ACCOUNT_POLICY_FAILURE_LAST = 0x1B
private const val ERRCONNECT_HYBRID_REQUIRED_BY_SERVER = 0x1E
private const val ERRCONNECT_NTLM_BLOCKED = 0x1F

private const val ERRINFO_RPC_INITIATED_DISCONNECT = 0x01
private const val ERRINFO_RPC_INITIATED_LOGOFF = 0x02
private const val ERRINFO_IDLE_TIMEOUT = 0x03
private const val ERRINFO_LOGON_TIMEOUT = 0x04
private const val ERRINFO_DISCONNECTED_BY_OTHER_CONNECTION = 0x05
private const val ERRINFO_SERVER_DENIED_CONNECTION = 0x07
private const val ERRINFO_SERVER_INSUFFICIENT_PRIVILEGES = 0x09
private const val ERRINFO_SERVER_FRESH_CREDENTIALS_REQUIRED = 0x0A
private const val ERRINFO_RPC_INITIATED_DISCONNECT_BY_USER = 0x0B
private const val ERRINFO_LOGOFF_BY_USER = 0x0C

private val NON_RETRYABLE_ERRINFO_TYPES = setOf(
    ERRINFO_RPC_INITIATED_DISCONNECT,
    ERRINFO_RPC_INITIATED_LOGOFF,
    ERRINFO_IDLE_TIMEOUT,
    ERRINFO_LOGON_TIMEOUT,
    ERRINFO_DISCONNECTED_BY_OTHER_CONNECTION,
    ERRINFO_SERVER_DENIED_CONNECTION,
    ERRINFO_SERVER_INSUFFICIENT_PRIVILEGES,
    ERRINFO_SERVER_FRESH_CREDENTIALS_REQUIRED,
    ERRINFO_RPC_INITIATED_DISCONNECT_BY_USER,
    ERRINFO_LOGOFF_BY_USER,
)

private val NON_RETRYABLE_CONNECT_TYPES =
    (AUTH_FAILURE_FIRST..AUTH_FAILURE_LAST).toSet() +
        (EXPIRED_OR_REVOKED_FIRST..EXPIRED_OR_REVOKED_LAST) +
        (ACCOUNT_POLICY_FAILURE_FIRST..ACCOUNT_POLICY_FAILURE_LAST) +
        setOf(ERRCONNECT_HYBRID_REQUIRED_BY_SERVER, ERRCONNECT_NTLM_BLOCKED)
