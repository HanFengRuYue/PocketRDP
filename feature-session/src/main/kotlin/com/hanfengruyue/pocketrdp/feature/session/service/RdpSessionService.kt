package com.hanfengruyue.pocketrdp.feature.session.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistry
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistrySnapshot
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRuntimeState
import com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag
import com.hanfengruyue.pocketrdp.feature.session.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

/**
 * Keep-alive foreground service for an active RDP session — the M7 "切到后台不断开" fix.
 *
 * ## What it does (and what it deliberately does NOT do)
 * It does **not** own any FreeRDP connection. Each retained SessionViewModel owns its own RdpClient,
 * native worker thread and BitmapBuffer. This service observes [RdpSessionRegistry] and pins the
 * *process* to the foreground-service importance bucket while at least one session is active.
 *
 * It additionally holds a [PowerManager.PARTIAL_WAKE_LOCK] + a [WifiManager.WifiLock] while the
 * session is connected so a screen-off / backgrounded socket isn't starved by CPU suspend or Wi-Fi
 * power-save. Both are released the instant the session ends — never held when no session is live.
 *
 * ## foregroundServiceType
 * `specialUse` (see AndroidManifest). NOT `dataSync`: Android 15 (API 35) caps dataSync at a
 * cumulative 6h/24h and force-stops it via `Service.onTimeout()`, which would tear down a long RDP
 * session. specialUse is exempt from that timeout.
 *
 * ## Lifecycle ownership
 * The [SessionViewModel][com.hanfengruyue.pocketrdp.feature.session.SessionViewModel] starts this
 * service when its RdpClient registers as active. This service self-stops only when the registry has
 * no active sessions left, so disconnecting one computer does not drop foreground keep-alive for the
 * others.
 */
/**
 * Hilt entry point to reach the singleton [RdpSessionRegistry] from this service. We deliberately use
 * [EntryPointAccessors] instead of `@AndroidEntryPoint` + `@Inject`: Hilt 2.55's member-injection
 * processor reads the injected class's Kotlin metadata with a kotlinx-metadata that caps at version
 * 2.1.0, and our classes compile to metadata 2.2.0 (Kotlin 2.2.21) — field injection into the
 * service crashed `hiltJavaCompileDebug` with "Provided Metadata instance has version 2.2.0". An
 * @EntryPoint accessor reads no member metadata, so it sidesteps that until the AGP-9/Hilt-2.56
 * upgrade is unblocked (see CLAUDE.md Hard constraints).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RdpSessionRegistryEntryPoint {
    fun rdpSessionRegistry(): RdpSessionRegistry
}

class RdpSessionService : Service() {

    private val sessionRegistry: RdpSessionRegistry by lazy {
        EntryPointAccessors.fromApplication(applicationContext, RdpSessionRegistryEntryPoint::class.java)
            .rdpSessionRegistry()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observing = false

    // Not a bound service — clients talk to it only via start()/stop() intents.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                // IMPORTANCE_LOW: a silent, non-intrusive persistent status notification.
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.session_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.session_notification_channel_description)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                // The notification action is process-wide: disconnect every active session.
                PocketLogger.i(TAG, "notification disconnect action -> disconnect all sessions")
                sessionRegistry.disconnectAll()
            }
            else -> {
                // Promote to a foreground service within the ~5 s window. specialUse type is required
                // on API 34+; gate the constant so older devices (which ignore the type) still work.
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    buildNotification(sessionRegistry.snapshot.value),
                    type,
                )
                observeSessions()
            }
        }
        // NOT sticky: if the OS/OEM kills us anyway (despite the FGS), there is no live connection
        // to restore, so a system-restarted service with a null intent would only show a zombie
        // notification. We rely on the FGS to PREVENT the kill; recovery after a hard kill is the
        // app-launch reconnect path + the OEM allow-list guide instead.
        return START_NOT_STICKY
    }

    private fun observeSessions() {
        if (observing) return
        observing = true
        sessionRegistry.snapshot
            .onEach { snapshot ->
                if (!snapshot.hasActiveSessions) {
                    shutdown()
                    return@onEach
                }
                if (snapshot.connectedCount > 0) acquireLocks() else releaseLocks()
                updateStatus(snapshot)
            }
            .launchIn(scope)
    }

    private fun acquireLocks() {
        if (cpuLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { PocketLogger.w(TAG, "acquire wake lock failed: ${it.message}") }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // FULL_HIGH_PERF keeps the radio powered regardless of screen state — the right choice
            // for holding a socket while backgrounded / screen-off. (LOW_LATENCY only takes effect
            // foreground + screen-on, so it is a no-op for the keep-alive case.)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKELOCK_TAG)
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { PocketLogger.w(TAG, "acquire wifi lock failed: ${it.message}") }
        PocketLogger.d(TAG, "session locks acquired")
    }

    private fun releaseLocks() {
        cpuLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        cpuLock = null
        wifiLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wifiLock = null
    }

    private fun updateStatus(snapshot: RdpSessionRegistrySnapshot) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(snapshot))
    }

    private fun shutdown() {
        releaseLocks()
        SessionKeepAliveFlag.setActive(this, false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(snapshot: RdpSessionRegistrySnapshot): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            // Bring the existing (singleTask) Activity to the front instead of a fresh task.
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, launch ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectPi = PendingIntent.getService(
            this, 1,
            Intent(this, RdpSessionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val content = buildRdpSessionNotificationContent(snapshot, rdpSessionNotificationStrings())
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, content.disconnectAction, disconnectPi)
        if (content.bigLines.isNotEmpty()) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(content.title)
                .setSummaryText(content.text)
            content.bigLines.forEach(style::addLine)
            builder.setStyle(style)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "RdpFgs"
        private const val CHANNEL_ID = "rdp_session"
        private const val NOTIF_ID = 0x5244 // 'R''D' — must be non-zero
        private const val WAKELOCK_TAG = "PocketRDP:session"

        private const val ACTION_DISCONNECT = "com.hanfengruyue.pocketrdp.action.DISCONNECT"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_STATUS = "status"

        /**
         * Start (or refresh the notification text of) the keep-alive service for the active session.
         * MUST be called while the app is in the foreground (the connect screen is visible) — Android
         * 31+ forbids starting a foreground service from the background.
         */
        fun start(context: Context, name: String, host: String, status: String) {
            val intent = Intent(context, RdpSessionService::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_STATUS, status)
            context.startForegroundService(intent)
        }

        /**
         * Tear the service down (releases locks + drops the notification via onDestroy). Uses
         * stopService so it works from the background too (no foreground-start restriction).
         * Idempotent — a no-op if the service isn't running.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RdpSessionService::class.java)) }
        }
    }
}

internal data class RdpSessionNotificationStrings(
    val defaultTitle: String,
    val connecting: String,
    val connected: String,
    val reconnecting: String,
    val failed: String,
    val disconnect: String,
    val disconnectAll: String,
    val multiTitleFormat: String,
    val multiSummaryFormat: String,
    val moreSessionsFormat: String,
)

internal data class RdpSessionNotificationContent(
    val title: String,
    val text: String,
    val bigLines: List<String>,
    val disconnectAction: String,
)

private fun Context.rdpSessionNotificationStrings(): RdpSessionNotificationStrings =
    RdpSessionNotificationStrings(
        defaultTitle = getString(R.string.session_notification_title),
        connecting = getString(R.string.session_notification_status_connecting),
        connected = getString(R.string.session_notification_status_connected),
        reconnecting = getString(R.string.session_notification_status_reconnecting),
        failed = getString(R.string.session_notification_status_failed),
        disconnect = getString(R.string.session_notification_disconnect_action),
        disconnectAll = getString(R.string.session_notification_disconnect_all_action),
        multiTitleFormat = getString(R.string.session_notification_multi_title),
        multiSummaryFormat = getString(R.string.session_notification_multi_summary),
        moreSessionsFormat = getString(R.string.session_notification_more_sessions),
    )

internal fun buildRdpSessionNotificationContent(
    snapshot: RdpSessionRegistrySnapshot,
    strings: RdpSessionNotificationStrings,
): RdpSessionNotificationContent {
    val sessions = snapshot.sessions
    if (snapshot.activeCount <= 1) {
        val info = sessions.firstOrNull()
        val status = statusLabel(info?.state ?: RdpSessionRuntimeState.CONNECTING, strings)
        val title = info?.displayName?.takeIf { it.isNotBlank() } ?: strings.defaultTitle
        val text = info?.hostLabel?.takeIf { it.isNotBlank() }?.let { "$status · $it" } ?: status
        return RdpSessionNotificationContent(
            title = title,
            text = text,
            bigLines = emptyList(),
            disconnectAction = strings.disconnect,
        )
    }

    val shown = sessions.take(MAX_NOTIFICATION_SESSION_LINES).map { info ->
        val name = info.displayName.takeIf { it.isNotBlank() }
            ?: info.hostLabel.takeIf { it.isNotBlank() }
            ?: strings.defaultTitle
        val status = statusLabel(info.state, strings)
        info.hostLabel.takeIf { it.isNotBlank() }
            ?.let { "$name · $status · $it" } ?: "$name · $status"
    }
    val extra = (sessions.size - MAX_NOTIFICATION_SESSION_LINES).coerceAtLeast(0)
    val lines = if (extra > 0) {
        shown + String.format(Locale.getDefault(), strings.moreSessionsFormat, extra)
    } else {
        shown
    }
    return RdpSessionNotificationContent(
        title = String.format(Locale.getDefault(), strings.multiTitleFormat, snapshot.activeCount),
        text = String.format(
            Locale.getDefault(),
            strings.multiSummaryFormat,
            snapshot.connectedCount,
            snapshot.connectingCount,
            snapshot.reconnectingCount,
        ),
        bigLines = lines,
        disconnectAction = strings.disconnectAll,
    )
}

private fun statusLabel(state: RdpSessionRuntimeState, strings: RdpSessionNotificationStrings): String =
    when (state) {
        RdpSessionRuntimeState.CONNECTING -> strings.connecting
        RdpSessionRuntimeState.CONNECTED -> strings.connected
        RdpSessionRuntimeState.RECONNECTING -> strings.reconnecting
    }

private const val MAX_NOTIFICATION_SESSION_LINES = 5
