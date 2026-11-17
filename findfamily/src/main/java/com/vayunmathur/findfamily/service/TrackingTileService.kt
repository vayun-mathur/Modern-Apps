package com.vayunmathur.findfamily.service
import com.vayunmathur.findfamily.platform.LocationServiceController

import android.annotation.SuppressLint

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that turns the whole FindFamily location-tracking service on
 * and off (GitHub #487). Tapping it flips the persisted [LocationServiceController.TRACKING_ENABLED_KEY]
 * flag and immediately starts/stops the foreground service; boot and the periodic
 * restart worker both honor the same flag via [LocationServiceController.syncServiceState].
 *
 * If fine-location permission isn't granted the tile can't run tracking, so tapping it
 * opens the app to grant permission instead of toggling.
 */
class TrackingTileService : TileService() {

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
        if (!LocationServiceController.hasFineLocationPermission(this)) {
            // Tracking can't run without location permission — send the user to the app to grant it.
            openApp()
            return
        }
        scope.launch {
            val enabled = LocationServiceController.isTrackingEnabled(this@TrackingTileService)
            LocationServiceController.setTrackingEnabled(this@TrackingTileService, !enabled)
            refreshTile()
        }
    }

    private fun refreshTile() {
        scope.launch {
            val tile = qsTile ?: return@launch
            val hasPermission = LocationServiceController.hasFineLocationPermission(this@TrackingTileService)
            val enabled = LocationServiceController.isTrackingEnabled(this@TrackingTileService)
            tile.state = if (enabled && hasPermission) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = when {
                !hasPermission -> getString(R.string.tile_subtitle_no_permission)
                enabled -> getString(R.string.tile_subtitle_on)
                else -> getString(R.string.tile_subtitle_off)
            }
            tile.updateTile()
        }
    }

    // The PendingIntent overload of startActivityAndCollapse only exists on API 34+;
    // the Intent overload below is the guarded pre-34 fallback and cannot be replaced there.
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
