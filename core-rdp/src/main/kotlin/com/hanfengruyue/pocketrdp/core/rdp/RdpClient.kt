package com.hanfengruyue.pocketrdp.core.rdp

import android.content.Context
import android.util.Log
import com.freerdp.freerdpcore.services.LibFreeRDP
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

    fun connect(params: RdpConnectionParams) {
        if (!LibFreeRDP.isNativeReady()) {
            emit(RdpEvent.Failed("native FreeRDP not built — see core-rdp/build.gradle.kts to enable CMake superbuild"))
            return
        }
        if (nativeInstance != 0L) {
            Log.w(TAG, "connect() called while previous session still alive — disconnecting first")
            disconnect()
        }

        emit(RdpEvent.Connecting)
        acceptedCertThumb = params.acceptedCertThumbprint
        val inst = LibFreeRDP.newInstance(context)
        if (inst == 0L) {
            emit(RdpEvent.Failed("freerdp_new returned 0"))
            return
        }
        nativeInstance = inst

        val args = buildCommandLine(params)
        Log.i(TAG, "Connecting with args (redacted): ${redact(args)}")
        if (!LibFreeRDP.setConnectionArgs(inst, args)) {
            emit(RdpEvent.Failed("freerdp_parse_arguments failed: ${LibFreeRDP.freerdp_get_last_error_string(inst)}"))
            LibFreeRDP.freeInstance(inst)
            nativeInstance = 0L
            return
        }

        // freerdp_connect blocks until disconnect, so run on its own thread.
        connectThread = Thread({
            val ok = LibFreeRDP.connect(inst)
            if (!ok) {
                emit(RdpEvent.Failed("freerdp_connect returned false: ${LibFreeRDP.freerdp_get_last_error_string(inst)}"))
            }
        }, "freerdp-connect-$inst").also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        val inst = nativeInstance
        if (inst == 0L) return
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
        if (inst != 0L) LibFreeRDP.sendKeyEvent(inst, scanCode, down)
    }

    fun sendUnicodeKey(codePoint: Int, down: Boolean) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, down)
    }

    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendCursorEvent(inst, x, y, flags)
    }

    fun sendClipboard(data: String) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendClipboardData(inst, data)
    }

    fun sendMonitorLayout(width: Int, height: Int) {
        val inst = nativeInstance
        if (inst != 0L) LibFreeRDP.sendMonitorLayout(inst, width, height)
    }

    fun hasH264(): Boolean = LibFreeRDP.hasH264Support()
    fun version(): String = LibFreeRDP.freerdp_get_version()

    private fun emit(event: RdpEvent) {
        _events.tryEmit(event)
    }

    private val eventListener = object : LibFreeRDP.EventListener {
        override fun OnPreConnect(inst: Long) {}
        override fun OnConnectionSuccess(inst: Long) { emit(RdpEvent.Connected) }
        override fun OnConnectionFailure(inst: Long) { emit(RdpEvent.Failed("connection failure")) }
        override fun OnDisconnecting(inst: Long) {}
        override fun OnDisconnected(inst: Long) { emit(RdpEvent.Disconnected(reason = null)) }
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
        } else {
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
        if (p.useH264) args += "/gfx:AVC444"
        if (p.useGfx) args += "/rfx"
        if (p.dynamicResolution) args += "/dynamic-resolution"
        if (p.desktopScaleFactor in 100..300) args += "/scale:${p.desktopScaleFactor}"

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
    }
}
