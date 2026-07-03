package com.hanfengruyue.pocketrdp.core.rdp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Kotlin facade over the FreeRDP JNI bridge (com.freerdp.freerdpcore.services.LibFreeRDP).
 *
 * The bridge calls our static callbacks (OnConnectionSuccess, OnGraphicsUpdate, ...) which
 * we route through [LibFreeRDP.setEventListener] / [LibFreeRDP.setUIEventListener] into the
 * Kotlin-side state flow + bitmap buffer.
 *
 * Bitmap flow note: native side does NOT push frame bytes. Instead it tells us *where* the
 * dirty region is via OnGraphicsUpdate, and we pull pixels into our own Bitmap by calling
 * LibFreeRDP.updateGraphics(inst, bitmap, x, y, w, h). See android_freerdp.c.
 */
@Singleton
class RdpClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val buffer: BitmapBuffer = BitmapBuffer()

    private val _events = MutableSharedFlow<RdpEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RdpEvent> = _events.asSharedFlow()

    private var nativeInstance: Long = 0L
    private var connectThread: Thread? = null
    private var acceptedCertThumb: String? = null

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
        PocketLogger.i(TAG, "connect() host=${params.host}:${params.port} user=${params.username} h264=${params.useH264} gfx=${params.useGfx} dynRes=${params.dynamicResolution}")
        if (!LibFreeRDP.isNativeReady()) {
            PocketLogger.e(TAG, "native FreeRDP not ready — refusing connect")
            emit(RdpEvent.Failed("native FreeRDP not built — see core-rdp/build.gradle.kts to enable CMake superbuild"))
            return
        }
        if (nativeInstance != 0L) {
            PocketLogger.w(TAG, "connect() called while previous session still alive — disconnecting first")
            disconnect()
        }

        emit(RdpEvent.Connecting)
        acceptedCertThumb = params.acceptedCertThumbprint
        lastHost = params.host
        lastPort = params.port
        val inst = LibFreeRDP.newInstance(context)
        if (inst == 0L) {
            PocketLogger.e(TAG, "LibFreeRDP.newInstance returned 0")
            emit(RdpEvent.Failed("freerdp_new returned 0"))
            return
        }
        nativeInstance = inst
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
        PocketLogger.d(TAG, "freerdp instance allocated: $inst")

        val args = buildCommandLine(params)
        PocketLogger.i(TAG, "args=${redact(args).joinToString(" ")}")
        if (!LibFreeRDP.setConnectionArgs(inst, args)) {
            val err = LibFreeRDP.freerdp_get_last_error_string(inst)
            PocketLogger.e(TAG, "freerdp_parse_arguments failed (last_error='$err')")
            emit(RdpEvent.Failed("freerdp_parse_arguments failed: $err"))
            LibFreeRDP.freeInstance(inst)
            nativeInstance = 0L
            return
        }

        // freerdp_connect blocks until disconnect, so run on its own thread.
        connectThread = Thread({
            PocketLogger.d(TAG, "freerdp_connect thread starting (inst=$inst)")
            val ok = LibFreeRDP.connect(inst)
            if (!ok) {
                val err = LibFreeRDP.freerdp_get_last_error_string(inst)
                PocketLogger.e(TAG, "freerdp_connect returned false (last_error='$err')")
                emit(RdpEvent.Failed("freerdp_connect returned false: $err"))
            }
        }, "freerdp-connect-$inst").also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        val inst = nativeInstance
        if (inst == 0L) return
        PocketLogger.i(TAG, "disconnect() inst=$inst")
        Thread {
            LibFreeRDP.disconnect(inst)
            LibFreeRDP.freeInstance(inst)
        }.apply { isDaemon = true }.start()
        nativeInstance = 0L
        buffer.release()
        emit(RdpEvent.Disconnected(reason = "user"))
    }

    fun sendKeyEvent(scanCode: Int, down: Boolean) {
        val inst = nativeInstance
        if (inst == 0L) {
            // Caller is sending input after disconnect — silently drop. This used to push to
            // a freed native instance.
            PocketLogger.w(TAG, "sendKeyEvent vk=0x${scanCode.toString(16)} down=$down dropped (no native instance)")
            return
        }
        if (keyEventLogCount < KEY_LOG_LIMIT) {
            keyEventLogCount++
            PocketLogger.d(TAG, "sendKeyEvent vk=0x${scanCode.toString(16)} down=$down (#$keyEventLogCount)")
        }
        if (down) markDiscreteInput()
        LibFreeRDP.sendKeyEvent(inst, scanCode, down)
    }

    fun sendUnicodeKey(codePoint: Int, down: Boolean) {
        val inst = nativeInstance
        if (inst == 0L) {
            PocketLogger.w(TAG, "sendUnicodeKey cp=$codePoint down=$down dropped (no native instance)")
            return
        }
        if (unicodeEventLogCount < KEY_LOG_LIMIT) {
            unicodeEventLogCount++
            PocketLogger.d(TAG, "sendUnicodeKey cp=$codePoint down=$down (#$unicodeEventLogCount)")
        }
        if (down) markDiscreteInput()
        LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, down)
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
        val inst = nativeInstance
        return inst != 0L && LibFreeRDP.isUnicodeInputSupported(inst)
    }

    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        val inst = nativeInstance
        if (inst != 0L) {
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
        val inst = nativeInstance
        if (inst != 0L) {
            // Same as cursor: arm latency only on a touch DOWN (the press), not MOVE/UP.
            if (action == RdpTouchAction.DOWN) markDiscreteInput()
            LibFreeRDP.sendTouch(inst, contactId, x, y, action)
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
        if (nativeInstance == 0L) return -1
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

    /**
     * Negotiated transport bitfield (see [LibFreeRDP.getTransportInfo]); -1 when no live session.
     * bits 0..3 = actual transport state (TCP/UDP-R/UDP-L/UDP2), bit 8 = UDP requested but
     * fallback, bit 9 = server requested multitransport, bits 4..7 = selected security protocol.
     */
    fun transportInfo(): Int {
        val inst = nativeInstance
        return if (inst == 0L) -1 else LibFreeRDP.getTransportInfo(inst)
    }

    /** UDP transport counters: bytes/packets counted below TLS, so they should track frp UDP traffic. */
    fun transportStats(): RdpTransportStats {
        val inst = nativeInstance
        if (inst == 0L) return RdpTransportStats()
        val raw = LibFreeRDP.getTransportStats(inst) ?: return RdpTransportStats()
        return RdpTransportStats(
            inBytes = raw.getOrElse(0) { 0L },
            outBytes = raw.getOrElse(1) { 0L },
            inPackets = raw.getOrElse(2) { 0L },
            outPackets = raw.getOrElse(3) { 0L },
            retransmits = raw.getOrElse(4) { 0L },
            failureStage = raw.getOrElse(5) { 0L },
            tunnelHr = raw.getOrElse(6) { 0L },
            socketError = raw.getOrElse(7) { 0L },
        )
    }

    fun sendClipboard(data: String) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendClipboardData(inst, data)
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
        val inst = nativeInstance
        if (inst == 0L) return false
        return LibFreeRDP.sendMonitorLayout(inst, width, height)
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
        if (nativeInstance == 0L) return@withContext -1
        runCatching {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, lastPort), LATENCY_PROBE_TIMEOUT_MS)
                ((System.nanoTime() - start) / NANOS_PER_MILLI).toInt().coerceIn(0, MAX_LATENCY_MS)
            }
        }.getOrDefault(-1)
    }

    private fun emit(event: RdpEvent) {
        _events.tryEmit(event)
    }

    private val eventListener = object : LibFreeRDP.EventListener {
        override fun OnPreConnect(inst: Long) { PocketLogger.d(TAG, "OnPreConnect inst=$inst") }
        override fun OnConnectionSuccess(inst: Long) {
            PocketLogger.i(TAG, "OnConnectionSuccess inst=$inst")
            emit(RdpEvent.Connected)
        }
        override fun OnConnectionFailure(inst: Long) {
            PocketLogger.e(TAG, "OnConnectionFailure inst=$inst")
            emit(RdpEvent.Failed("connection failure"))
        }
        override fun OnDisconnecting(inst: Long) { PocketLogger.d(TAG, "OnDisconnecting inst=$inst") }
        override fun OnDisconnected(inst: Long) {
            PocketLogger.i(TAG, "OnDisconnected inst=$inst")
            emit(RdpEvent.Disconnected(reason = null))
        }
    }

    private val uiEventListener = object : LibFreeRDP.UIEventListener {
        override fun OnAuthenticate(inst: Long, username: StringBuilder, domain: StringBuilder, password: StringBuilder): Boolean {
            emit(RdpEvent.CredentialsRequired)
            return false
        }

        override fun OnGatewayAuthenticate(inst: Long, username: StringBuilder, domain: StringBuilder, password: StringBuilder): Boolean = false

        override fun OnVerifyCertificateEx(inst: Long, host: String, port: Long, commonName: String, subject: String, issuer: String, fingerprint: String, flags: Long): Int {
            val sha256 = fingerprint.lowercase().replace(":", "")
            return if (acceptedCertThumb != null && acceptedCertThumb.equals(sha256, ignoreCase = true)) {
                1
            } else {
                emit(RdpEvent.CertificatePrompt(host, sha256, isChange = false))
                0
            }
        }

        override fun OnVerifyChangedCertificateEx(inst: Long, host: String, port: Long, commonName: String, subject: String, issuer: String, fingerprint: String, oldSubject: String, oldIssuer: String, oldFingerprint: String, flags: Long): Int {
            emit(RdpEvent.CertificatePrompt(host, fingerprint, isChange = true))
            return 0
        }

        override fun OnGraphicsUpdate(inst: Long, x: Int, y: Int, w: Int, h: Int) {
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
            LibFreeRDP.updateGraphics(inst, target, x, y, w, h)
            // The back buffer is one published generation behind — re-apply the region that changed
            // in the previous frame so the buffer we're about to publish is a complete mirror of the
            // gdi framebuffer (otherwise the swapped-in frame would be missing last frame's update).
            // BUT skip that second copy when this frame's rect already fully covers the stale region
            // (the common case for video / full-screen repaints) — re-copying it would just be a
            // redundant full-screen blit, the dominant per-frame cost behind the "卡顿". (issue #1)
            buffer.staleRect()?.let { r ->
                if (!r.isEmpty && !(x <= r.left && y <= r.top && x + w >= r.right && y + h >= r.bottom)) {
                    LibFreeRDP.updateGraphics(inst, target, r.left, r.top, r.width(), r.height())
                }
            }
            // Swap back→front: the UI's free-running render loop now reads a complete, stable frame,
            // eliminating the mid-write tearing on large updates ("逐行扫描").
            buffer.commitFrame(x, y, w, h)
            emit(RdpEvent.GraphicsUpdated(x, y, w, h))
        }

        override fun OnGraphicsResize(inst: Long, width: Int, height: Int, bpp: Int) {
            PocketLogger.i(TAG, "OnGraphicsResize ${width}x$height bpp=$bpp")
            val bm = buffer.resize(width, height)
            emit(RdpEvent.GraphicsResized(width, height, bm))
        }

        override fun OnRemoteClipboardChanged(inst: Long, data: String) {
            emit(RdpEvent.ClipboardReceived(data))
        }

        override fun OnPointerSet(inst: Long, pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) {}
        override fun OnPointerSetNull(inst: Long) {}
        override fun OnPointerSetDefault(inst: Long) {}
    }

    init {
        if (LibFreeRDP.isNativeReady()) {
            LibFreeRDP.setEventListener(eventListener)
            LibFreeRDP.setUIEventListener(uiEventListener)
            PocketLogger.i(TAG, "FreeRDP native ready ver=${LibFreeRDP.freerdp_get_version()} h264=${LibFreeRDP.hasH264Support()}")
        } else {
            PocketLogger.w(TAG, "Native FreeRDP library not loaded; running in UI-stub mode")
            Log.w(TAG, "Native FreeRDP library not loaded; running in UI-stub mode")
        }
    }

    private fun buildCommandLine(p: RdpConnectionParams): Array<String> {
        val args = mutableListOf(
            "freerdp",
            "/gdi:sw",
            "/v:${p.host}",
            "/port:${p.port}",
            "/u:${p.username}",
            "/cert:ignore",
            "/size:${p.initialWidth}x${p.initialHeight}",
            "/bpp:${p.colorDepth}",
        )
        if (p.password.isNotEmpty()) args += "/p:${p.password}"
        if (p.domain.isNotEmpty()) args += "/d:${p.domain}"
        // Graphics pipeline selection. H.264 is the decisive fix for the video "画面卡顿": the Windows
        // host runs AVC444ModePreferred=1, so once the client can decode H.264 the server uses a
        // hardware-encoded path (tiny bandwidth + cheap decode) instead of software RemoteFX
        // full-screen blits.
        //
        // We request AVC444 (full 4:4:4 chroma — crisp coloured text/edges). This is sound ONLY
        // because the H.264 decoder is FFmpeg (the native build is WITH_OPENH264=OFF + WITH_FFMPEG=ON,
        // FFmpeg static-linked into libfreerdp3). AVC444 is a dual H.264 stream that FreeRDP recombines
        // into YUV444 via its own prim_YUV; with the OLD OpenH264 backend that combine produced a
        // diagonal magenta/green chroma grid (the plane strides OpenH264 emitted didn't line up),
        // field-confirmed. FFmpeg's decoder output strides feed the combine correctly → clean AVC444.
        // If the decoder ever reverts to OpenH264, drop this back to /gfx:AVC420 (single YUV420 stream,
        // no auxiliary-chroma combine, the only H.264 mode that's clean on OpenH264). When H.264 isn't
        // available at all we use GFX RemoteFX progressive (/gfx:RFX) — mutually exclusive with AVC.
        // 画质优先 → AVC444 (full 4:4:4, crisp text). 流畅优先 → AVC420 (single YUV420 stream: ~half
        // the FFmpeg software-decode cost + no prim_YUV444 recombine → lower control latency, at the
        // cost of 4:2:0 chroma). AVC420 is also clean with the FFmpeg backend (the magenta/green grid
        // was an OpenH264-only recombine-stride bug, and AVC420 has no recombine at all). Mutually
        // exclusive with RFX — never both.
        when {
            p.useH264 && LibFreeRDP.hasH264Support() ->
                args += if (p.preferAvc420) "/gfx:AVC420" else "/gfx:AVC444"
            p.useGfx -> args += "/gfx:RFX"
        }
        // Enable the rdpei dynamic channel so InputMode.TOUCH can forward native Windows multi-touch
        // contacts. Harmless when unused (TRACKPAD mode never sends touch) and auto-skipped by the
        // server if it doesn't support RDPEI.
        args += "/multitouch"
        if (p.dynamicResolution) args += "/dynamic-resolution"
        // RDP-UDP multitransport capability (UDP-R/UDP-L/UDP2). Real UDP traffic requires the
        // native tunnel implementation to establish an RDP-UDP/RDPEMT channel AND the public endpoint
        // (frp/server/firewall) to expose UDP on the same host:port; otherwise the session stays on TCP.
        // CRITICAL — emit the explicit DISABLE form when the user turns it off: FreeRDP defaults
        // SupportMultitransport=TRUE (+ non-zero MultitransportFlags) in settings.c, so simply
        // NOT adding "/multitransport" is a no-op — UDP would still be negotiated (field bug:
        // 关闭 UDP 仍走 UDP). The disable form MUST be "-multitransport" (the '-' sigil sets
        // SupportMultitransport=FALSE and zeroes MultitransportFlags). "/multitransport:off" would
        // FAIL freerdp_parse_arguments with COMMAND_LINE_ERROR_UNEXPECTED_VALUE — a BOOL option in
        // the slash/colon syntax rejects a trailing value (same class as the /scale trap above).
        args += if (p.useMultitransport) "/multitransport" else "-multitransport"
        // Auto-reconnect on an UNEXPECTED drop (not a user disconnect): FreeRDP transparently
        // re-establishes the session using the server's auto-reconnect cookie. Mitigates flaky links
        // — e.g. a mobile NAT / public-IP port-forward (host:port) silently dropping an idle TCP
        // connection after ~18 s, observed as "连上几秒就断开". Harmless when the link is stable.
        args += "/auto-reconnect"
        // 低延迟视觉 (performanceFlags bit PERF_LOW_LATENCY_VISUALS): force-disable the remote desktop's
        // wallpaper + themes so each frame the server encodes (and the client decodes) carries less
        // high-frequency detail. MUST use the '-' sigil (BOOL-option disable form): "-wallpaper" sets
        // FreeRDP_DisableWallpaper=TRUE via the enable=FALSE path. "/wallpaper:off" would FAIL
        // freerdp_parse_arguments with COMMAND_LINE_ERROR_UNEXPECTED_VALUE (a BOOL option rejects a
        // trailing value — same trap as /multitransport). Modest on a LAN under H.264/GFX (the whole
        // desktop is video-encoded regardless), so it's opt-in per connection, default off.
        if (p.performanceFlags and PERF_LOW_LATENCY_VISUALS != 0) {
            args += "-wallpaper"
            args += "-themes"
        }
        // Remote desktop DPI scaling. Use /scale-desktop (FreeRDP_DesktopScaleFactor, accepts any
        // 100..500) — NOT /scale, which parse_scale_options() hard-limits to {100,140,180}: every
        // value >180 silently collapsed to 180, so the 100–300 % slider's 200/250/300 were
        // indistinguishable (field bug: 缩放滑块只有三档真正生效). /scale-desktop honours the exact
        // value, giving the slider real continuous effect. Clamp to the accepted range so an
        // out-of-range value can never fail freerdp_parse_arguments.
        val scale = p.desktopScaleFactor.coerceIn(MIN_DESKTOP_SCALE, MAX_DESKTOP_SCALE)
        args += "/scale-desktop:$scale"

        val clipDir = if (p.redirectClipboard) "all" else "off"
        args += "/clipboard:use-selection:primary,direction-to:$clipDir"

        // File redirection (rdpdr DRIVE device). Export an app-owned POSIX folder so the remote sees a
        // "PocketRDP" drive in Explorer. Only emit when enabled AND a real path is supplied (the caller
        // mkdirs() it before connect — FreeRDP's freerdp_path_valid silently SKIPS a non-existent drive
        // path, non-fatally). The drive NAME must contain no space/colon. Disabling is just omission:
        // FreeRDP_DeviceRedirection defaults FALSE, so there's no BOOL-disable trap here (unlike
        // /multitransport). A SAF content:// URI can NOT be used — WinPR opens the path with native
        // open()/stat(), so the path must be a true filesystem path.
        if (p.redirectFiles && !p.drivePath.isNullOrBlank()) {
            args += "/drive:PocketRDP,${p.drivePath}"
        }

        // 远程音频路由（等价 mstsc 的「播放位置」三选项）。rdpsnd + OpenSL ES 播放后端已静态编译进
        // libfreerdp-client3.so（DT_NEEDED libOpenSLES.so + CLIENT_RDPSND_SUBSYSTEM_TABLE，已二进制验证），
        // AudioPlayback=TRUE 时 rdpsnd 作为静态通道由 freerdp_client_load_addins 自动加载（cmdline.c
        // staticChannels 表），从 C 层 OpenSL ES 直接出声，无需 JNI 回调。/audio-mode 是枚举值选项
        // （必须带值；非 BOOL，不踩 /opt:off 陷阱）；0 用显式 none 强制 AudioPlayback+RemoteConsoleAudio 皆 FALSE。
        when (p.soundMode) {
            1 -> args += "/audio-mode:redirect" // 控制端（手机）播放：重定向到客户端，opensles 出声
            2 -> args += "/audio-mode:server"   // 被控端（远端机）播放：RemoteConsoleAudio，声音留在被控电脑
            else -> args += "/audio-mode:none"  // 0 = 停用音频
        }
        return args.toTypedArray()
    }

    private fun redact(args: Array<String>): List<String> =
        args.map { if (it.startsWith("/p:")) "/p:****" else it }

    companion object {
        private const val TAG = "RdpClient"
        // performanceFlags bit (mirrors ConnectionEntity.PERF_LOW_LATENCY_VISUALS) — drop wallpaper+themes.
        // core-rdp does not depend on core-data, so the value is duplicated here; keep the two in sync.
        const val PERF_LOW_LATENCY_VISUALS = 1
        // Per-connection cap on keyboard-input debug logs. Captures the start-of-typing
        // pattern (which is what we need to investigate "input → disconnect" timing) without
        // flooding logs when the user holds a key or pastes long text.
        private const val KEY_LOG_LIMIT = 50
        private const val DEFAULT_RDP_PORT = 3389
        // Remote desktop DPI scale (/scale-desktop) bounds. FreeRDP accepts 100..500; we cap at the
        // edit screen's slider range so the UI value maps 1:1 to what the remote receives.
        private const val MIN_DESKTOP_SCALE = 100
        private const val MAX_DESKTOP_SCALE = 300
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
    }
}
