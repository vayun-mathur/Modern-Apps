package com.vayunmathur.findfamily.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.UserManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

internal fun LocationTrackingService.startDirectBootTracking() {
    if (directBootJob?.isActive == true) return
    if (!unlockReceiverRegistered) {
        registerReceiver(
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        unlockReceiverRegistered = true
    }
    directBootJob = serviceScope.launch {
        val ctx = this@startDirectBootTracking
        // Expected on the first boot after this ships, and after a factory reset: there is
        // nothing to publish with yet. Seeding happens below once the user unlocks.
        if (!DirectBootStore.isSeeded(ctx)) {
            Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "no device-protected mirror yet; idle until first unlock")
            return@launch
        }
        if (!DirectBootStore.isTrackingEnabled(ctx)) {
            Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "tracking switched off by the user; staying idle")
            return@launch
        }
        if (!Networking.initDirectBoot(DirectBootStore.store(ctx))) {
            Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "identity unavailable from the mirror; staying idle")
            return@launch
        }

        withContext(Dispatchers.Main) {
            registerSensors()
            isMoving = true
            lastMovementTime = System.currentTimeMillis()
            setupLocationUpdates()
        }
        // Inbound delivery needs Room to persist anything, so the pre-unlock socket is
        // publish-only. Peers' locations are picked up on reconnect after unlock.
        Networking.startLive(serviceScope, onLocations = {}, onUwb = {})

        val sharing = DirectBootStore.isGlobalSharingEnabled(ctx)
        val targets = if (sharing) DirectBootStore.roster(ctx) else emptyList()
        publishRoster = targets
        Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "running pre-unlock, sharing=$sharing targets=${targets.size}")
        while (isActive) {
            publishDirectBoot(targets)
            delay(30.seconds)
        }
    }
}

/** The pre-unlock equivalent of [syncHeartbeat]: publish only, no database, no enrichment. */
// Broad catch is deliberate: one peer's publish failure must not abort the pre-unlock publish loop.
@Suppress("TooGenericExceptionCaught")
internal suspend fun LocationTrackingService.publishDirectBoot(targets: List<DirectBootStore.Target>) {
    val location = lastKnownLocation ?: run {
        Log.debug(LocationTrackingService.TAG_DIRECT_BOOT, "no fix yet")
        return
    }
    if (targets.isEmpty()) return
    val battery = runCatching {
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }.getOrDefault(0f)
    val lv = LocationValue(
        Networking.userid,
        Coord(location.latitude, location.longitude),
        if (location.hasSpeed()) location.speed else 0f,
        location.accuracy,
        Clock.System.now(),
        battery,
    )
    val locSummary = "${location.latitude},${location.longitude} acc=${location.accuracy}"
    Log.dev(
        LocationTrackingService.TAG_DIRECT_BOOT,
        "publishing $locSummary to ${targets.size} peer(s)"
    )
    targets.forEach {
        try {
            Networking.publishLocation(lv, it.id, it.bundle, it.pairId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "publish to ${it.id.toULong()} failed", e)
        }
    }
}

// Broad catch is deliberate: handover failures must be logged, never crash the unlock receiver path.
@Suppress("TooGenericExceptionCaught")
internal fun LocationTrackingService.onUserUnlocked() {
    serviceScope.launch {
        Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "user unlocked; handing over to the normal path")
        directBootJob?.cancelAndJoin()
        directBootJob = null
        try {
            Networking.promoteToUnlocked(
                repository,
                DataStoreUtils.getInstance(this@onUserUnlocked),
                getString(R.string.me_label),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.status(LocationTrackingService.TAG_DIRECT_BOOT, "handover failed", e)
        }
        startTracking()
    }
}
