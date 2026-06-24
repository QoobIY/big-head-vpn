package app.bighead.vpn.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.bighead.vpn.R
import app.bighead.vpn.core.ProfileStore
import app.bighead.vpn.ui.MainActivity

class BigHeadVpnTileService : TileService() {
    private val store by lazy { ProfileStore(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (store.vpnRunning) {
            startService(Intent(this, BigHeadVpnService::class.java).setAction(BigHeadVpnService.ACTION_STOP))
            store.vpnRunning = false
            updateTile()
            return
        }

        if (store.activeProfile == null || VpnService.prepare(this) != null) {
            openApp()
            return
        }

        startService(Intent(this, BigHeadVpnService::class.java).setAction(BigHeadVpnService.ACTION_START))
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = store.vpnRunning
        tile.label = "Big Head VPN"
        tile.subtitle = if (running) "Включён" else "Выключен"
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_status)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    3,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestListeningState(
                    context,
                    ComponentName(context, BigHeadVpnTileService::class.java),
                )
            }
        }
    }
}
