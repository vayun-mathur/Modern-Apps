package com.vayunmathur.findfamily.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.service.SharingTileService
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Single source of truth for whether the [LocationTrackingService] should be
 * running and for (re)starting / stopping it accordingly.
 *
 * The service runs whenever fine (precise) location permission is granted **and**
 * the user hasn't turned tracking off (the [TRACKING_ENABLED_KEY] flag, toggled
 * from the Quick Settings tile — see TrackingTileService). Sharing toggles are
 * enforced inside the heartbeat (we only publish to users with sendingEnabled=true)
 * rather than by stopping the service, so that UWB inbox draining, waypoint
 * entry/exit, low-battery alerts, and receiving peers' locations continue to work
 * even when the user pauses sharing or on fresh install before any contact is added.
 */
object LocationServiceController {

    /** Persisted on/off switch for the whole tracking service (default on). */
    const val TRACKING_ENABLED_KEY = "tracking_enabled"

    /**
     * Persisted on/off switch for publishing this device's location to anyone, toggled
     * from the Quick Settings sharing tile (default on — see SharingTileService).
     *
     * Deliberately separate from the per-person `sendingEnabled` flags so pausing and
     * resuming restores whoever was being shared with, and from [TRACKING_ENABLED_KEY]
     * so the service keeps running: peers' locations, waypoint entry/exit and UWB
     * tracker reporting all continue while sharing is paused.
     */
    const val GLOBAL_SHARING_ENABLED_KEY = "global_sharing_enabled"

    /**
     * Whether this phone acts as a finder for other people's powered-off devices.
     *
     * MANDATORY. There is no toggle: the finder half is part of what the app is, and the
     * Bluetooth permission behind it is now requested on the initial permission screen alongside
     * location. The key is retained only so an existing install's stored value is not orphaned;
     * nothing reads it any more.
     */
    const val CROWD_FINDING_ENABLED_KEY = "crowd_finding_enabled"

    fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** Whether the user has left tracking enabled. Defaults to true (opt-out, not opt-in). */
    suspend fun isTrackingEnabled(context: Context): Boolean =
        DataStoreUtils.getInstance(context).getBooleanAwait(TRACKING_ENABLED_KEY, true)

    /** Persist the tracking on/off choice and immediately start/stop the service to match. */
    suspend fun setTrackingEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(TRACKING_ENABLED_KEY, enabled)
        syncServiceState(context)
    }

    /** Whether outbound sharing is on. Defaults to true (opt-out, not opt-in). */
    suspend fun isGlobalSharingEnabled(context: Context): Boolean =
        DataStoreUtils.getInstance(context).getBooleanAwait(GLOBAL_SHARING_ENABLED_KEY, true)

    /** [isGlobalSharingEnabled] as a stream, for the UI to grey out the per-person switches. */
    fun globalSharingEnabledFlow(context: Context): Flow<Boolean> =
        DataStoreUtils.getInstance(context).booleanFlow(GLOBAL_SHARING_ENABLED_KEY, true)

    /**
     * Persist the outbound-sharing choice. The next heartbeat picks it up; the service
     * itself is left alone, so this never stops receiving or tracker reporting.
     */
    suspend fun setGlobalSharingEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(GLOBAL_SHARING_ENABLED_KEY, enabled)
        SharingTileService.requestRefresh(context)
    }

    /** Finding is mandatory: every install acts as a finder, so this is always true. */
    const val CROWD_FINDING_ENABLED = true

    /**
     * [CROWD_FINDING_ENABLED] as a stream. Constant now that finding is mandatory - kept as a
     * Flow so the collector in the service is unchanged, and so a future re-introduction of a
     * user control does not have to re-plumb the call site.
     */
    @Suppress("UNUSED_PARAMETER")
    fun crowdFindingEnabledFlow(context: Context): Flow<Boolean> = flowOf(true)

    suspend fun setCrowdFindingEnabled(context: Context, enabled: Boolean) {
        DataStoreUtils.getInstance(context).setBoolean(CROWD_FINDING_ENABLED_KEY, enabled)
    }

    /**
     * True iff the user is sharing their location with at least one *other*
     * person (the self user is excluded). Reads directly from the DB so the
     * answer is correct regardless of whether the UI/ViewModel is alive.
     */
    suspend fun isSharingEnabled(context: Context): Boolean {
        val ds = DataStoreUtils.getInstance(context)
        val selfId = try { ds.getLongAwait("userid") } catch (_: Exception) { ds.getLong("userid") }
        return FindFamilyRepository.get(context).getAllUsers().any { it.sendingEnabled && it.id != selfId }
    }

    /**
     * Start the service if eligible, otherwise make sure it is stopped. Safe to
     * call from any context (worker, boot, ViewModel, permission refresh, tile).
     * Eligible = fine-location permission granted AND tracking not turned off.
     */
    suspend fun syncServiceState(context: Context) {
        val appContext = context.applicationContext
        val eligible = hasFineLocationPermission(appContext) && isTrackingEnabled(appContext)
        val intent = Intent(appContext, LocationTrackingService::class.java)
        withContext(Dispatchers.Main) {
            if (eligible) {
                try {
                    appContext.startForegroundService(intent)
                } catch (_: Exception) {
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }

    /** Unconditionally stop the service. */
    fun stop(context: Context) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, LocationTrackingService::class.java)
        )
    }

    /**
     * Boot-time start for the window before the first unlock.
     *
     * Deliberately does not consult [isTrackingEnabled] — that reads the credential-encrypted
     * DataStore, which is unreadable until the passcode is entered. Eligibility comes from the
     * device-protected mirror instead, and an unseeded mirror means we stay off rather than
     * guess. Never stops the service, since "not eligible yet" here only means "cannot tell".
     */
    suspend fun syncServiceStateLocked(context: Context) {
        val appContext = context.applicationContext
        if (!hasFineLocationPermission(appContext)) return
        if (!DirectBootStore.isSeeded(appContext)) return
        if (!DirectBootStore.isTrackingEnabled(appContext)) return
        withContext(Dispatchers.Main) {
            runCatching {
                appContext.startForegroundService(
                    Intent(appContext, LocationTrackingService::class.java)
                )
            }
        }
    }
}
