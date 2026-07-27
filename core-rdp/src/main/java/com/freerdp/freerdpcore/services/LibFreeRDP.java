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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"unused", "WeakerAccess"})
public class LibFreeRDP {

    private static final String TAG = "LibFreeRDP";
    private static boolean mHasH264 = false;
    private static final LongSparseArray<Boolean> mInstanceState = new LongSparseArray<>();
    private static final Map<Long, EventListener> eventListeners = new HashMap<>();
    private static final Map<Long, UIEventListener> uiEventListeners = new HashMap<>();

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
        // H.264 uses Android MediaCodec when available, with FFmpeg statically linked into
        // libfreerdp3 as the software fallback (WITH_OPENH264=OFF). There is no separate
        // libopenh264.so to pre-load here.
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
        } catch (LinkageError | RuntimeException e) {
            // Keep nativeReady=false so a missing/incompatible packaged library produces a
            // diagnostic UI state. Do not swallow fatal VM errors such as OutOfMemoryError.
            Log.w(TAG, "Native FreeRDP library not available: " + e);
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
    // Atomic versioned transport snapshot. The native contract is exactly 36 longs.
    private static native long[] freerdp_get_transport_snapshot(long inst);
    public static native long freerdp_get_last_error_code(long inst);
    public static native String freerdp_get_last_error_string(long inst);

    public static void registerEventListener(long inst, EventListener l) {
        synchronized (eventListeners) { eventListeners.put(inst, l); }
    }
    public static void unregisterEventListener(long inst) {
        synchronized (eventListeners) { eventListeners.remove(inst); }
    }
    public static void registerUIEventListener(long inst, UIEventListener l) {
        synchronized (uiEventListeners) { uiEventListeners.put(inst, l); }
    }
    public static void unregisterUIEventListener(long inst) {
        synchronized (uiEventListeners) { uiEventListeners.remove(inst); }
    }
    private static EventListener eventListenerFor(long inst) {
        synchronized (eventListeners) {
            return eventListeners.get(inst);
        }
    }
    private static UIEventListener uiEventListenerFor(long inst) {
        synchronized (uiEventListeners) {
            return uiEventListeners.get(inst);
        }
    }

    public static long newInstance(Context context) { return freerdp_new(context); }

    public static void freeInstance(long inst) {
        final boolean active;
        synchronized (mInstanceState) {
            active = mInstanceState.get(inst, false);
        }
        // Never wait indefinitely for a callback to flip Java state. Native teardown aborts an
        // in-progress handshake, waits for the worker with a bounded timeout, and safely defers the
        // final free if a plugin is still stuck.
        if (active) freerdp_disconnect(inst);
        freerdp_free(inst);
        synchronized (mInstanceState) {
            mInstanceState.remove(inst);
            mInstanceState.notifyAll();
        }
    }

    public static boolean connect(long inst) {
        synchronized (mInstanceState) {
            if (mInstanceState.get(inst, false)) throw new RuntimeException("already connected");
            // freerdp_connect starts the native worker asynchronously. Mark the instance in-flight
            // before JNI so an immediate disconnect/free cannot release it during the
            // CreateThread -> OnPreConnect startup window.
            mInstanceState.put(inst, true);
        }
        boolean started = freerdp_connect(inst);
        if (!started) {
            synchronized (mInstanceState) {
                mInstanceState.put(inst, false);
                mInstanceState.notifyAll();
            }
        }
        return started;
    }

    public static boolean disconnect(long inst) {
        final boolean active;
        synchronized (mInstanceState) {
            active = mInstanceState.get(inst, false);
        }
        // JNI may synchronously trigger callbacks that also acquire mInstanceState.
        return !active || freerdp_disconnect(inst);
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
    public static long[] getTransportSnapshot(long inst) {
        return freerdp_get_transport_snapshot(inst);
    }

    // ============================================================
    // Static callbacks invoked from native (android_jni_callback.c)
    // Signatures MUST match: see android_freerdp.c freerdp_callback() call sites.
    // ============================================================

    public static void OnPreConnect(long inst) {
        synchronized (mInstanceState) { mInstanceState.put(inst, true); mInstanceState.notifyAll(); }
        EventListener l = eventListenerFor(inst);
        if (l != null) l.OnPreConnect(inst);
    }
    public static void OnConnectionSuccess(long inst) {
        EventListener l = eventListenerFor(inst);
        if (l != null) l.OnConnectionSuccess(inst);
    }
    public static void OnConnectionFailure(long inst) {
        synchronized (mInstanceState) { mInstanceState.put(inst, false); mInstanceState.notifyAll(); }
        EventListener l = eventListenerFor(inst);
        if (l != null) l.OnConnectionFailure(inst);
    }
    public static void OnDisconnecting(long inst) {
        EventListener l = eventListenerFor(inst);
        if (l != null) l.OnDisconnecting(inst);
    }
    public static void OnDisconnected(long inst) {
        synchronized (mInstanceState) { mInstanceState.put(inst, false); mInstanceState.notifyAll(); }
        EventListener l = eventListenerFor(inst);
        if (l != null) l.OnDisconnected(inst);
    }

    public static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password) {
        UIEventListener l = uiEventListenerFor(inst);
        return l != null && l.OnAuthenticate(inst, username, domain, password);
    }
    public static boolean OnGatewayAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password) {
        UIEventListener l = uiEventListenerFor(inst);
        return l != null && l.OnGatewayAuthenticate(inst, username, domain, password);
    }
    public static int OnVerifyCertificateEx(long inst, String host, long port, String commonName, String subject, String issuer, String fingerprint, long flags) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l == null) return 0;
        return l.OnVerifyCertificateEx(inst, host, port, commonName, subject, issuer, fingerprint, flags);
    }
    public static int OnVerifyChangedCertificateEx(long inst, String host, long port, String commonName, String subject, String issuer, String fingerprint, String oldSubject, String oldIssuer, String oldFingerprint, long flags) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l == null) return 0;
        return l.OnVerifyChangedCertificateEx(inst, host, port, commonName, subject, issuer, fingerprint, oldSubject, oldIssuer, oldFingerprint, flags);
    }

    public static void OnGraphicsUpdate(long inst, int x, int y, int w, int h) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnGraphicsUpdate(inst, x, y, w, h);
    }
    public static void OnGraphicsResize(long inst, int width, int height, int bpp) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnGraphicsResize(inst, width, height, bpp);
    }
    // android_post_connect() emits OnSettingsChanged right before OnConnectionSuccess.
    // If this method is missing the native java_callback_void leaves a pending
    // NoSuchMethodError on the JNI thread, and the very next freerdp_callback()
    // (OnConnectionSuccess) is rejected by ART's strict JNI check → SIGABRT on connect.
    // Route it through OnGraphicsResize so Kotlin allocates the framebuffer eagerly.
    public static void OnSettingsChanged(long inst, int width, int height, int bpp) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnGraphicsResize(inst, width, height, bpp);
    }
    public static void OnRemoteClipboardChanged(long inst, String data) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnRemoteClipboardChanged(inst, data);
    }
    public static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnPointerSet(inst, pixels, width, height, hotX, hotY);
    }
    public static void OnPointerSetNull(long inst) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnPointerSetNull(inst);
    }
    public static void OnPointerSetDefault(long inst) {
        UIEventListener l = uiEventListenerFor(inst);
        if (l != null) l.OnPointerSetDefault(inst);
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
