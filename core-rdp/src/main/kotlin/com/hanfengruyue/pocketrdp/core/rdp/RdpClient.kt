package com.hanfengruyue.pocketrdp.core.rdp

import android.content.Context
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
        LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, down)
    }

    /**
     * Whether the connected server negotiated unicode keyboard input (FreeRDP_UnicodeInput).
     *
     * Critical for input routing: feeding a unicode keyboard event to a server that did NOT
     * advertise unicode support makes FreeRDP's Android event loop (android_process_event) treat
     * the rejected input PDU as a fatal error and break out of the connection loop — observed in
     * the field as "type one character → instant disconnect" (OnDisconnecting fires ~6 ms after
     * the first sendUnicodeKey). Callers must fall back to the scancode path (or drop the char)
     * for non-ASCII when this returns false.
     *
     * Returns false when there's no live instance, so input is naturally skipped after teardown.
     */
    fun isUnicodeInputSupported(): Boolean {
        val inst = nativeInstance
        return inst != 0L && LibFreeRDP.isUnicodeInputSupported(inst)
    }

    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendCursorEvent(inst, x, y, flags)
    }

    /**
     * Forward a native multi-touch contact (issue: 调用 Windows 原生触屏). [action] is one of
     * [RdpTouchAction] (DOWN/MOVE/UP); [contactId] is the finger id tracked across the gesture.
     * Silently dropped (returns) when there's no live instance or the rdpei channel isn't up yet —
     * never tears down the session.
     */
    fun sendTouch(contactId: Int, x: Int, y: Int, action: Int) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendTouch(inst, contactId, x, y, action)
    }

    /**
     * Negotiated transport bitfield (see [LibFreeRDP.getTransportInfo]); -1 when no live session.
     * bit0 = RDP-UDP multitransport active (UDP), bits 1+ = selected security protocol. The status
     * badge maps this to [RdpTransport].
     */
    fun transportInfo(): Int {
        val inst = nativeInstance
        return if (inst == 0L) -1 else LibFreeRDP.getTransportInfo(inst)
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
        when {
            p.useH264 && LibFreeRDP.hasH264Support() -> args += "/gfx:AVC444"
            p.useGfx -> args += "/gfx:RFX"
        }
        // Enable the rdpei dynamic channel so InputMode.TOUCH can forward native Windows multi-touch
        // contacts. Harmless when unused (TRACKPAD mode never sends touch) and auto-skipped by the
        // server if it doesn't support RDPEI.
        args += "/multitouch"
        if (p.dynamicResolution) args += "/dynamic-resolution"
        // RDP-UDP multitransport (UDP-R + UDP-L/FEC). Core RDP protocol, present in the prebuilt
        // .so (not a codec), and auto-falls-back to TCP if the server doesn't negotiate it — so it
        // is safe to request. Improves responsiveness on lossy/high-latency links.
        if (p.useMultitransport) args += "/multitransport"
        // FreeRDP 3.x parse_scale_options() hard-rejects anything outside {100, 140, 180}.
        // Snap the stored DPI to the nearest legal bucket so a saved value like 200 (the
        // ConnectionEntity default) doesn't cause freerdp_parse_arguments to fail with
        // COMMAND_LINE_ERROR_UNEXPECTED_VALUE — which Kotlin surfaces as the unhelpful
        // "freerdp_parse_arguments failed: Success" snackbar (last_error_code is unset
        // during arg parsing).
        val quantizedScale = when {
            p.desktopScaleFactor < 100 -> null
            p.desktopScaleFactor < 120 -> 100
            p.desktopScaleFactor < 160 -> 140
            else -> 180
        }
        if (quantizedScale != null) args += "/scale:$quantizedScale"

        val clipDir = if (p.redirectClipboard) "all" else "off"
        args += "/clipboard:use-selection:primary,direction-to:$clipDir"

        // Sound redirection: 0=off 1=local-play 2=remote
        when (p.soundMode) {
            1 -> args += "/sound"
            2 -> args += "/microphone"
        }
        return args.toTypedArray()
    }

    private fun redact(args: Array<String>): List<String> =
        args.map { if (it.startsWith("/p:")) "/p:****" else it }

    companion object {
        private const val TAG = "RdpClient"
        // Per-connection cap on keyboard-input debug logs. Captures the start-of-typing
        // pattern (which is what we need to investigate "input → disconnect" timing) without
        // flooding logs when the user holds a key or pastes long text.
        private const val KEY_LOG_LIMIT = 50
        private const val DEFAULT_RDP_PORT = 3389
        private const val LATENCY_PROBE_TIMEOUT_MS = 2000
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_LATENCY_MS = 9999
    }
}
