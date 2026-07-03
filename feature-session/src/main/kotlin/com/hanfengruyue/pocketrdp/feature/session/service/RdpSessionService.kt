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
import com.hanfengruyue.pocketrdp.core.rdp.RdpClient
import com.hanfengruyue.pocketrdp.core.rdp.RdpEvent
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

/**
 * Keep-alive foreground service for an active RDP session — the M7 "切到后台不断开" fix.
 *
 * ## What it does (and what it deliberately does NOT do)
 * It does **not** own the FreeRDP connection. The connection object, the native worker thread and
 * the [com.hanfengruyue.pocketrdp.core.rdp.BitmapBuffer] all live in the `@Singleton` [RdpClient]
 * at **process** scope, exactly as before. This service's only job is to pin the *process* to the
 * "foreground service" importance bucket so the OS / OEM low-memory killer stops reclaiming it the
 * moment the user switches apps — which is the actual cause of the background disconnect (a
 * single-Activity Compose app's `SessionViewModel.onCleared()` does **not** fire on plain
 * backgrounding, so nothing in our code tears the socket down; the process just gets killed).
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
 * The [SessionViewModel][com.hanfengruyue.pocketrdp.feature.session.SessionViewModel] is the single
 * brain: it [start]s this service when a connection begins and [stop]s it when it gives up
 * reconnecting. This service self-stops only on a **user-initiated** disconnect
 * ([RdpEvent.Disconnected] with `reason == "user"` — emitted by both [RdpClient.disconnect] and the
 * notification's 断开 action), so an *unexpected* drop (reason == null) leaves the service running
 * while the ViewModel auto-reconnects.
 */
/**
 * Hilt entry point to reach the `@Singleton` [RdpClient] from this service. We deliberately use
 * [EntryPointAccessors] instead of `@AndroidEntryPoint` + `@Inject`: Hilt 2.55's member-injection
 * processor reads the injected class's Kotlin metadata with a kotlinx-metadata that caps at version
 * 2.1.0, and our classes compile to metadata 2.2.0 (Kotlin 2.2.21) — field injection into the
 * service crashed `hiltJavaCompileDebug` with "Provided Metadata instance has version 2.2.0". An
 * @EntryPoint accessor reads no member metadata, so it sidesteps that until the AGP-9/Hilt-2.56
 * upgrade is unblocked (see CLAUDE.md Hard constraints).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RdpClientEntryPoint {
    fun rdpClient(): RdpClient
}

class RdpSessionService : Service() {

    private val rdpClient: RdpClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, RdpClientEntryPoint::class.java)
            .rdpClient()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observing = false

    private var connectionName: String = ""
    private var connectionHost: String = ""
    private var lastStatusText: String? = null

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
                // The 断开 notification action: a deliberate user teardown. disconnect() emits
                // Disconnected(reason="user"), which our own observer turns into stopSelf().
                PocketLogger.i(TAG, "notification disconnect action -> disconnect()")
                rdpClient.disconnect()
            }
            else -> {
                connectionName = intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
                    ?: connectionName
                connectionHost = intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() }
                    ?: connectionHost
                // Caller-supplied status lets the (foreground) initial-connect path set "已连接"
                // directly, in case our event subscription started after a very fast Connected fired.
                intent?.getStringExtra(EXTRA_STATUS)?.takeIf { it.isNotBlank() }?.let { lastStatusText = it }
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
                    buildNotification(lastStatusText ?: getString(R.string.session_notification_status_connecting)),
                    type,
                )
                observeEvents()
            }
        }
        // NOT sticky: if the OS/OEM kills us anyway (despite the FGS), there is no live connection
        // to restore, so a system-restarted service with a null intent would only show a zombie
        // notification. We rely on the FGS to PREVENT the kill; recovery after a hard kill is the
        // app-launch reconnect path + the OEM allow-list guide instead.
        return START_NOT_STICKY
    }

    private fun observeEvents() {
        if (observing) return
        observing = true
        rdpClient.events
            .onEach { event ->
                when (event) {
                    is RdpEvent.Connecting -> updateStatus(getString(R.string.session_notification_status_connecting))
                    is RdpEvent.Connected -> {
                        updateStatus(getString(R.string.session_notification_status_connected))
                        acquireLocks()
                    }
                    is RdpEvent.Disconnected -> {
                        releaseLocks()
                        if (event.reason == "user") {
                            // User tore the session down (disconnect button / left the screen).
                            shutdown()
                        } else {
                            // Unexpected drop — the ViewModel is auto-reconnecting; stay alive.
                            updateStatus(getString(R.string.session_notification_status_reconnecting))
                        }
                    }
                    is RdpEvent.Failed -> {
                        releaseLocks()
                        updateStatus(getString(R.string.session_notification_status_failed))
                    }
                    else -> Unit
                }
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

    private fun updateStatus(text: String) {
        lastStatusText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun shutdown() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(statusText: String): Notification {
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
        val title = connectionName.takeIf { it.isNotBlank() }
            ?: getString(R.string.session_notification_title)
        val text = connectionHost.takeIf { it.isNotBlank() }
            ?.let { "$statusText · $it" } ?: statusText
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.session_notification_disconnect_action), disconnectPi)
            .build()
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
