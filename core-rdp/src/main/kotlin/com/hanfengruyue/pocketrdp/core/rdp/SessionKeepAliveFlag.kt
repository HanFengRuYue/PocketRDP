package com.hanfengruyue.pocketrdp.core.rdp

import android.content.Context

/**
 * A tiny persisted flag recording "a keep-alive session was meant to be running". Set true while the
 * keep-alive foreground service is intended to be up, and false when the session is torn down
 * cleanly (user disconnect / left the screen / gave up reconnecting).
 *
 * If the process is killed in the background (OS low-memory killer or an OEM battery killer), the
 * teardown path never runs, so the flag stays **true** — on next launch that is the signal "we were
 * killed while trying to stay connected". The connection-list reads it (combined with
 * `ActivityManager` exit reasons, to exclude ordinary app crashes) to offer the OEM keep-alive guide.
 *
 * Lives in `:core-rdp` because both `:feature-session` (the writer, from SessionViewModel) and
 * `:app` (the reader) depend on this module.
 */
object SessionKeepAliveFlag {
    private const val PREFS = "pocketrdp_keepalive"
    private const val KEY_ACTIVE = "session_active"

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    /** Returns whether a session was still flagged active, and clears the flag (read-once). */
    fun consumeWasActive(context: Context): Boolean {
        val p = prefs(context)
        val was = p.getBoolean(KEY_ACTIVE, false)
        if (was) p.edit().putBoolean(KEY_ACTIVE, false).apply()
        return was
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
