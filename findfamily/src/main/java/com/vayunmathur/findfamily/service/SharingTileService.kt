package com.vayunmathur.findfamily.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.platform.LocationServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that pauses and resumes publishing this device's location
 * (GitHub #648), as opposed to the tracking tile from #487 which stops the whole
 * service.
 *
 * It flips [LocationServiceController.GLOBAL_SHARING_ENABLED_KEY] rather than the
 * per-person switches, so resuming shares with exactly the people the user had
 * chosen, and it deliberately leaves the foreground service running: peers'
 * locations, waypoint entry/exit and UWB tracker reporting continue while paused.
 */
class SharingTileService : TileService() {

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
        scope.launch {
            val enabled = LocationServiceController.isGlobalSharingEnabled(this@SharingTileService)
            LocationServiceController.setGlobalSharingEnabled(this@SharingTileService, !enabled)
            refreshTile()
        }
    }

    private fun refreshTile() {
        scope.launch {
            val tile = qsTile ?: return@launch
            val enabled = LocationServiceController.isGlobalSharingEnabled(this@SharingTileService)
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = getString(
                if (enabled) R.string.tile_subtitle_on else R.string.tile_subtitle_off
            )
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** Helpers for refreshing the sharing Quick Settings tile. */
    companion object {
        /**
         * Ask the system to bind the tile so it re-reads the flag. Without this a
         * change made inside the app leaves a stale tile until the shade is reopened.
         */
        fun requestRefresh(context: Context) {
            val app = context.applicationContext
            TileService.requestListeningState(app, ComponentName(app, SharingTileService::class.java))
        }
    }
}
