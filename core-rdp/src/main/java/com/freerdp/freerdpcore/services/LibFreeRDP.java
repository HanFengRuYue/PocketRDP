/*
   Minimal LibFreeRDP shim for PocketRDP

   Reuses the JNI bridge from FreeRDP/client/Android/Studio/freeRDPCore but strips the
   dependencies on FreeRDP's own BookmarkBase/SessionState/ApplicationSettingsActivity so we
   can drive everything from Kotlin code in :core-rdp without dragging in androidx.appcompat,
   sqlcipher, room 2.8.4 etc.

   Class FQN (com.freerdp.freerdpcore.services.LibFreeRDP) MUST stay unchanged: the native
   layer hard-codes this path in android_freerdp_jni.h:JAVA_LIBFREERDP_CLASS.

   Original copyright: 2013 Thincast Technologies GmbH, Author: Martin Fleisz. MPL 2.0.
*/
package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.collection.LongSparseArray;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"unused", "WeakerAccess"})
public class LibFreeRDP {

    private static final String TAG = "LibFreeRDP";
    private static EventListener listener;
    private static UIEventListener uiListener;
    private static boolean mHasH264 = false;
    private static final LongSparseArray<Boolean> mInstanceState = new LongSparseArray<>();

    public static final long VERIFY_CERT_FLAG_NONE = 0x00;
    public static final long VERIFY_CERT_FLAG_LEGACY = 0x02;
    public static final long VERIFY_CERT_FLAG_REDIRECT = 0x10;
    public static final long VERIFY_CERT_FLAG_GATEWAY = 0x20;
    public static final long VERIFY_CERT_FLAG_CHANGED = 0x40;
    public static final long VERIFY_CERT_FLAG_MISMATCH = 0x80;
    public static final long VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
    public static final long VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

    /** Required by the JNI bridge — it instantiates LibFreeRDP via NewObject(<init>). */
    public LibFreeRDP() { }

    public static boolean isNativeReady() { return nativeReady; }
    private static boolean nativeReady = false;

    static {
        // H.264 decode is provided by FFmpeg statically linked into libfreerdp3 (WITH_FFMPEG=ON,
        // WITH_OPENH264=OFF), so there is no separate libopenh264.so to pre-load here.
        try {
            System.loadLibrary("freerdp-android");
            String version = freerdp_get_jni_version();
            String[] versions = version.split("[\\.-]");
            if (versions.length > 0) {
                System.loadLibrary("freerdp-client" + versions[0]);
                System.loadLibrary("freerdp" + versions[0]);
                System.loadLibrary("winpr" + versions[0]);
            }
            Pattern p = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*");
            Matcher m = p.matcher(version);
            if (!m.matches() || m.groupCount() < 3) {
                throw new RuntimeException("Bad native version: " + version);
            }
            int major = Integer.parseInt(Objects.requireNonNull(m.group(1)));
            int minor = Integer.parseInt(Objects.requireNonNull(m.group(2)));
            int patch = Integer.parseInt(Objects.requireNonNull(m.group(3)));
            if (major > 2) mHasH264 = freerdp_has_h264();
            else if (minor > 5) mHasH264 = freerdp_has_h264();
            else if (minor == 5 && patch >= 1) mHasH264 = freerdp_has_h264();
            else throw new RuntimeException("Native library too old: " + version);
            nativeReady = true;
            Log.i(TAG, "Loaded FreeRDP " + version + ", H264=" + mHasH264);
        } catch (Throwable e) {
            // M2: native libs are not built yet (CMake superbuild deferred).
            // Keep nativeReady=false so RdpClient can show a friendly "not ready" state
            // instead of crashing the app on every connect.
            Log.w(TAG, "Native FreeRDP library not available (M2 deferred): " + e);
        }
    }

    public static boolean hasH264Support() { return mHasH264; }

    private static native boolean freerdp_has_h264();
    private static native String freerdp_get_jni_version();
    public static native String freerdp_get_version();
    public static native String freerdp_get_build_revision();
    public static native String freerdp_get_build_config();
    private static native long freerdp_new(Context context);
    private static native void freerdp_free(long inst);
    private static native boolean freerdp_parse_arguments(long inst, String[] args);
    private static native boolean freerdp_connect(long inst);
    private static native boolean freerdp_disconnect(long inst);
    private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y, int width, int height);
    private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);
    private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_send_unicodekey_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_is_unicode_input_supported(long inst);
    private static native boolean freerdp_send_clipboard_data(long inst, String data);
    private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);
    // Native RDPEI multi-touch: forwards one touch contact (action 0=down/1=move/2=up). Requires
    // the rdpei dynamic channel (negotiated via /multitouch) — returns false until it's up.
    private static native boolean freerdp_send_touch(long inst, int contactId, int x, int y, int action);
    // Transport bitfield (see RdpClient.transportInfo): bits 0..3 = actual transport state
    // (TCP/UDP-R/UDP-L/UDP2), bit 8 = UDP requested but fell back to TCP, bit 9 =
    // multitransport requested, bits 4..7 = selected security protocol.
    private static native int freerdp_get_transport_info(long inst);
    // UDP transport counters/diagnostics:
    // [inBytes, outBytes, inPackets, outPackets, retransmits, failureStage, tunnelHr, socketError].
    private static native long[] freerdp_get_transport_stats(long inst);
    public static native String freerdp_get_last_error_string(long inst);

    public static void setEventListener(EventListener l) { listener = l; }
    public static void setUIEventListener(UIEventListener l) { uiListener = l; }

    public static long newInstance(Context context) { return freerdp_new(context); }

    public static void freeInstance(long inst) {
        synchronized (mInstanceState) {
            if (mInstanceState.get(inst, false)) freerdp_disconnect(inst);
            while (mInstanceState.get(inst, false)) {
                try { mInstanceState.wait(); }
                catch (InterruptedException ignored) { throw new RuntimeException(); }
            }
        }
        freerdp_free(inst);
    }

    public static boolean connect(long inst) {
        synchronized (mInstanceState) {
            if (mInstanceState.get(inst, false)) throw new RuntimeException("already connected");
        }
        return freerdp_connect(inst);
    }

    public static boolean disconnect(long inst) {
        synchronized (mInstanceState) {
            if (mInstanceState.get(inst, false)) return freerdp_disconnect(inst);
            return true;
        }
    }

    public static boolean cancelConnection(long inst) { return freerdp_disconnect(inst); }

    /** Pass through any FreeRDP CLI argument array. Caller assembles the args in Kotlin. */
    public static boolean setConnectionArgs(long inst, String[] args) {
        return freerdp_parse_arguments(inst, args);
    }

    public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int w, int h) {
        return freerdp_update_graphics(inst, bitmap, x, y, w, h);
    }
    public static boolean sendCursorEvent(long inst, int x, int y, int flags) {
        return freerdp_send_cursor_event(inst, x, y, flags);
    }
    public static boolean sendKeyEvent(long inst, int keycode, boolean down) {
        return freerdp_send_key_event(inst, keycode, down);
    }
    public static boolean sendUnicodeKeyEvent(long inst, int keycode, boolean down) {
        return freerdp_send_unicodekey_event(inst, keycode, down);
    }
    /**
     * Whether the server negotiated INPUT_FLAG_UNICODE (FreeRDP_UnicodeInput). Sending a unicode
     * keyboard event to a server that didn't advertise it makes the native event loop tear down
     * the whole session — callers must gate the unicode path on this.
     */
    public static boolean isUnicodeInputSupported(long inst) {
        return freerdp_is_unicode_input_supported(inst);
    }
    public static boolean sendClipboardData(long inst, String data) {
        return freerdp_send_clipboard_data(inst, data);
    }
    public static boolean sendMonitorLayout(long inst, int w, int h) {
        return freerdp_send_monitor_layout(inst, w, h);
    }
    public static boolean sendTouch(long inst, int contactId, int x, int y, int action) {
        return freerdp_send_touch(inst, contactId, x, y, action);
    }
    public static int getTransportInfo(long inst) {
        return freerdp_get_transport_info(inst);
    }
    public static long[] getTransportStats(long inst) {
        return freerdp_get_transport_stats(inst);
    }

    // ============================================================
    // Static callbacks invoked from native (android_jni_callback.c)
    // Signatures MUST match: see android_freerdp.c freerdp_callback() call sites.
    // ============================================================

    public static void OnPreConnect(long inst) {
        synchronized (mInstanceState) { mInstanceState.put(inst, true); mInstanceState.notifyAll(); }
        if (listener != null) listener.OnPreConnect(inst);
    }
    public static void OnConnectionSuccess(long inst) {
        if (listener != null) listener.OnConnectionSuccess(inst);
    }
    public static void OnConnectionFailure(long inst) {
        synchronized (mInstanceState) { mInstanceState.put(inst, false); mInstanceState.notifyAll(); }
        if (listener != null) listener.OnConnectionFailure(inst);
    }
    public static void OnDisconnecting(long inst) {
        if (listener != null) listener.OnDisconnecting(inst);
    }
    public static void OnDisconnected(long inst) {
        synchronized (mInstanceState) { mInstanceState.put(inst, false); mInstanceState.notifyAll(); }
        if (listener != null) listener.OnDisconnected(inst);
    }

    public static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password) {
        return uiListener != null && uiListener.OnAuthenticate(inst, username, domain, password);
    }
    public static boolean OnGatewayAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password) {
        return uiListener != null && uiListener.OnGatewayAuthenticate(inst, username, domain, password);
    }
    public static int OnVerifyCertificateEx(long inst, String host, long port, String commonName, String subject, String issuer, String fingerprint, long flags) {
        if (uiListener == null) return 0;
        return uiListener.OnVerifyCertificateEx(inst, host, port, commonName, subject, issuer, fingerprint, flags);
    }
    public static int OnVerifyChangedCertificateEx(long inst, String host, long port, String commonName, String subject, String issuer, String fingerprint, String oldSubject, String oldIssuer, String oldFingerprint, long flags) {
        if (uiListener == null) return 0;
        return uiListener.OnVerifyChangedCertificateEx(inst, host, port, commonName, subject, issuer, fingerprint, oldSubject, oldIssuer, oldFingerprint, flags);
    }

    public static void OnGraphicsUpdate(long inst, int x, int y, int w, int h) {
        if (uiListener != null) uiListener.OnGraphicsUpdate(inst, x, y, w, h);
    }
    public static void OnGraphicsResize(long inst, int width, int height, int bpp) {
        if (uiListener != null) uiListener.OnGraphicsResize(inst, width, height, bpp);
    }
    // android_post_connect() emits OnSettingsChanged right before OnConnectionSuccess.
    // If this method is missing the native java_callback_void leaves a pending
    // NoSuchMethodError on the JNI thread, and the very next freerdp_callback()
    // (OnConnectionSuccess) is rejected by ART's strict JNI check → SIGABRT on connect.
    // Route it through OnGraphicsResize so Kotlin allocates the framebuffer eagerly.
    public static void OnSettingsChanged(long inst, int width, int height, int bpp) {
        if (uiListener != null) uiListener.OnGraphicsResize(inst, width, height, bpp);
    }
    public static void OnRemoteClipboardChanged(long inst, String data) {
        if (uiListener != null) uiListener.OnRemoteClipboardChanged(inst, data);
    }
    public static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY) {
        if (uiListener != null) uiListener.OnPointerSet(inst, pixels, width, height, hotX, hotY);
    }
    public static void OnPointerSetNull(long inst) {
        if (uiListener != null) uiListener.OnPointerSetNull(inst);
    }
    public static void OnPointerSetDefault(long inst) {
        if (uiListener != null) uiListener.OnPointerSetDefault(inst);
    }

    // ============================================================

    public interface EventListener {
        void OnPreConnect(long inst);
        void OnConnectionSuccess(long inst);
        void OnConnectionFailure(long inst);
        void OnDisconnecting(long inst);
        void OnDisconnected(long inst);
    }

    public interface UIEventListener {
        boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password);
        boolean OnGatewayAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password);
        int OnVerifyCertificateEx(long inst, String host, long port, String commonName, String subject, String issuer, String fingerprint, long flags);
        int OnVerifyChangedCertificateEx(long inst, String host, long port, String commonName, String subject, String issuer, String fingerprint, String oldSubject, String oldIssuer, String oldFingerprint, long flags);
        void OnGraphicsUpdate(long inst, int x, int y, int w, int h);
        void OnGraphicsResize(long inst, int width, int height, int bpp);
        void OnRemoteClipboardChanged(long inst, String data);
        void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY);
        void OnPointerSetNull(long inst);
        void OnPointerSetDefault(long inst);
    }
}
