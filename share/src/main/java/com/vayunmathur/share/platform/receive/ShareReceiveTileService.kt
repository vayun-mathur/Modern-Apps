package com.vayunmathur.share.platform.receive

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vayunmathur.share.MainActivity
import com.vayunmathur.share.R
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The only control for whether `:share` is receivable.
 *
 * Tapping flips the persisted [ShareReceiveController.RECEIVE_ENABLED_KEY] flag and reconciles
 * the foreground service immediately; boot and a sticky restart honour the same flag through
 * [ShareReceiveController.syncServiceState], so the tile and the service can never disagree.
 *
 * Without `POST_NOTIFICATIONS` the whole feature is invisible — notifications *are* the receive
 * UI — so the tile refuses to enable and sends the user to the app to grant it instead.
 */
class ShareReceiveTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!ShareReceiveController.hasNotificationPermission(this)) {
            openApp()
            return
        }
        scope.launch {
            val enabled = ShareReceiveController.isReceiveEnabled(this@ShareReceiveTileService)
            ShareReceiveController.setReceiveEnabled(this@ShareReceiveTileService, !enabled)
            refreshTile()
        }
    }

    private fun refreshTile() {
        scope.launch {
            val tile = qsTile ?: return@launch
            val hasPermission = ShareReceiveController.hasNotificationPermission(this@ShareReceiveTileService)
            val enabled = ShareReceiveController.isReceiveEnabled(this@ShareReceiveTileService)
            tile.state = if (enabled && hasPermission) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = when {
                !hasPermission -> getString(R.string.share_tile_no_permission)
                enabled -> ShareReceiveController.localName
                else -> getString(UiR.string.off)
            }
            tile.updateTile()
        }
    }

    // PendingIntent overload exists only on API 34+; the Intent overload is the 31-33 fallback.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
