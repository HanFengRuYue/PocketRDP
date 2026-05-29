package com.hanfengruyue.pocketrdp.core.rdp

import android.content.Context
import android.util.Log
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
            val bm = buffer.current.value ?: return
            LibFreeRDP.updateGraphics(inst, bm, x, y, w, h)
            buffer.notifyDirty(x, y, w, h)
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
        // /gfx:AVC444 needs H.264 support compiled into FreeRDP (WITH_OPENH264=ON).
        // Our prebuilt jniLibs are built with OPENH264=OFF; silently downgrade so
        // the connection attempt doesn't die at parse_command_line.
        if (p.useH264 && LibFreeRDP.hasH264Support()) args += "/gfx:AVC444"
        if (p.useGfx) args += "/rfx"
        if (p.dynamicResolution) args += "/dynamic-resolution"
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
    }
}
