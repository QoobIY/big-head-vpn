package app.bighead.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.bighead.vpn.R
import app.bighead.vpn.core.ProfileStore
import app.bighead.vpn.ui.MainActivity

class BigHeadVpnService : VpnService() {
    private val store by lazy { ProfileStore(this) }
    private var engine: LightweightTunnelEngine? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn(stopSelfAfter = true)
            ACTION_NOTIFICATION_DISMISSED -> restoreNotificationIfNeeded()
            else -> startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn(stopSelfAfter = false)
        super.onDestroy()
    }

    private fun startVpn() {
        stopping = false
        val profile = store.activeProfile
        if (profile == null) {
            Toast.makeText(this, "Добавьте VLESS или Hysteria профиль", Toast.LENGTH_LONG).show()
            DebugLog.append(this, "start failed: no active profile")
            stopSelf()
            return
        }
        DebugLog.append(this, "start requested: ${profile.protocol.label} ${profile.name}")
        showForegroundNotification("Подключение", profile.name)
        store.vpnConnecting = true
        store.vpnRunning = false
        broadcastState()

        worker = Thread {
            runCatching {
                LightweightTunnelEngine(this, store.selectedPackages).also {
                    engine = it
                    it.start(profile)
                }
            }.onSuccess {
                DebugLog.append(this, "start success")
                store.vpnConnecting = false
                store.vpnRunning = true
                store.vpnStartedAt = System.currentTimeMillis()
                Handler(Looper.getMainLooper()).post {
                    showForegroundNotification("VPN включён", profile.name)
                    BigHeadVpnTileService.requestTileRefresh(this)
                    broadcastState()
                }
            }.onFailure { error ->
                DebugLog.append(this, "start failed", error)
                store.vpnConnecting = false
                store.vpnRunning = false
                store.vpnStartedAt = 0L
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, error.message ?: "VPN engine is not ready", Toast.LENGTH_LONG).show()
                    BigHeadVpnTileService.requestTileRefresh(this)
                    broadcastState()
                    stopSelf()
                }
            }
        }
        worker?.start()
    }

    private fun stopVpn(stopSelfAfter: Boolean) {
        if (stopping && engine == null && !store.vpnRunning && !store.vpnConnecting) {
            if (stopSelfAfter) stopSelf()
            return
        }
        stopping = true
        DebugLog.append(this, "stop requested")
        runCatching { engine?.stop() }
            .onSuccess { DebugLog.append(this, "engine stopped") }
            .onFailure { DebugLog.append(this, "engine stop failed", it) }
        engine = null
        worker = null
        store.vpnConnecting = false
        store.vpnRunning = false
        store.vpnStartedAt = 0L
        BigHeadVpnTileService.requestTileRefresh(this)
        broadcastState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (stopSelfAfter) stopSelf()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun restoreNotificationIfNeeded() {
        if (!store.vpnRunning && !store.vpnConnecting) return
        val profile = store.activeProfile ?: return
        DebugLog.append(this, "notification dismissed, restoring")
        val title = if (store.vpnRunning) "VPN включён" else "Подключение"
        showForegroundNotification(title, profile.name)
    }

    private fun showForegroundNotification(title: String, text: String) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BigHeadVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deleteIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, BigHeadVpnService::class.java).setAction(ACTION_NOTIFICATION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setDeleteIntent(deleteIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_status),
                    "Выключить",
                    stopIntent,
                ).build(),
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder
            .build()
            .apply {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            description = "Статус и управление VPN"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "app.bighead.vpn.STOP"
        const val ACTION_START = "app.bighead.vpn.START"
        private const val ACTION_NOTIFICATION_DISMISSED = "app.bighead.vpn.NOTIFICATION_DISMISSED"
        const val ACTION_STATE_CHANGED = "app.bighead.vpn.STATE_CHANGED"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 10
    }
}
