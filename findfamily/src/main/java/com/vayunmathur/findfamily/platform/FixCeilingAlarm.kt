package com.vayunmathur.findfamily.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.vayunmathur.findfamily.domain.ForcedFixPolicy
import com.vayunmathur.findfamily.util.LocationServiceController
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.publishDirectBoot
import com.vayunmathur.findfamily.util.syncHeartbeat
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/*
 * The 15-minute active-check ceiling (#895 follow-up), Doze half.
 *
 * Owner requirement: "minimum once every 15 minutes, location should be checked immediately
 * (not get last location, get current location) even if no movement is detected."
 * ForcedFixPolicy decides when that check is due; this file makes sure something is awake to
 * ask. The 30 s heartbeat is a coroutine delay, which does not advance while the CPU sleeps,
 * so on its own a dozing phone would never run the check at all.
 *
 * - While the phone shares with anyone, one AlarmManager ELAPSED_REALTIME_WAKEUP alarm is kept
 *   armed for the moment the next check is due. Exact + allow-while-idle when the app may
 *   schedule exact alarms (USE_EXACT_ALARM is install-granted on API 33+, SCHEDULE_EXACT_ALARM
 *   is granted by default on 31-32): it fires on time in Doze. Otherwise inexact
 *   allow-while-idle, armed early enough that the end of its batching window is still inside
 *   the ceiling (ForcedFixPolicy.ceilingAlarmDelayMs). This app ships via F-Droid and its own
 *   store, so Play's alarm-app-only policy for USE_EXACT_ALARM does not apply; calendar and
 *   todo already declare it the same way.
 * - The alarm hits [FixCeilingReceiver], which takes a short wake lock and pokes the running
 *   service. The service runs the ordinary keep-alive tick, waits for the forced fix (which
 *   holds its own wake lock and publishes its result) and re-arms.
 * - The alarm moves on every forced-fix attempt and is cancelled when sharing stops or the
 *   service is destroyed.
 */

private const val TAG_CEILING = "FF-FixCeiling"

/** Arms and cancels the ceiling alarm, and bridges the CPU from the alarm to the service. */
internal object FixCeilingAlarm {

    /** The alarm's broadcast action ([FixCeilingReceiver]). */
    const val ACTION_ALARM = "com.vayunmathur.findfamily.action.FIX_CEILING_ALARM"

    /** The service command the receiver sends ([LocationTrackingService.onStartCommand]). */
    const val ACTION_CHECK = "com.vayunmathur.findfamily.action.FIX_CEILING_CHECK"

    private const val REQUEST_CODE = 895

    /**
     * Holds the CPU from the alarm broadcast until the service has started the forced fix
     * (which then holds its own lock). Released explicitly; the timeout is a backstop.
     */
    private const val BRIDGE_WAKE_LOCK_TIMEOUT_MS = 60_000L

    private const val BRIDGE_WAKE_LOCK_TAG = "findfamily:fix-ceiling"

    @Volatile
    private var bridgeWakeLock: PowerManager.WakeLock? = null

    /**
     * Arms (or moves) the alarm for the next due check, measured from [lastAttemptMs]
     * (elapsedRealtime of the last active current-location request; null = due now).
     * Returns true when the alarm is exact.
     */
    fun arm(context: Context, nowMs: Long, lastAttemptMs: Long?): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        val operation = pendingIntent(context)
        if (alarmManager.canScheduleExactAlarms()) {
            val triggerAt = nowMs + ForcedFixPolicy.ceilingAlarmDelayMs(nowMs, lastAttemptMs, exact = true)
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
                return true
            } catch (_: SecurityException) {
                // Revoked between the check and the call: fall through to inexact.
            }
        }
        val triggerAt = nowMs + ForcedFixPolicy.ceilingAlarmDelayMs(nowMs, lastAttemptMs, exact = false)
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
        return false
    }

    /** Cancels the alarm, if one is armed. */
    fun cancel(context: Context) {
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            alarmIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(operation)
        operation.cancel()
    }

    fun acquireWakeLock(context: Context) {
        val lock = bridgeWakeLock ?: synchronized(this) {
            bridgeWakeLock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BRIDGE_WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false) }
                ?.also { bridgeWakeLock = it }
        }
        lock?.acquire(BRIDGE_WAKE_LOCK_TIMEOUT_MS)
    }

    fun releaseWakeLock() {
        bridgeWakeLock?.takeIf { it.isHeld }?.release()
    }

    /** Whether the bridge wake lock is held right now (tests and diagnostics). */
    val isWakeLockHeld: Boolean get() = bridgeWakeLock?.isHeld == true

    private fun alarmIntent(context: Context) =
        Intent(context, FixCeilingReceiver::class.java).setAction(ACTION_ALARM)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        alarmIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * The ceiling alarm's receiver: keeps the CPU up and hands the check to the running service.
 *
 * Plain startService, not startForegroundService: the service is already in the foreground
 * whenever the alarm is armed, so the app is not "in the background" and the start is
 * allowed. If the process lost its service meanwhile the start is refused, and the
 * ServiceRestartWorker brings tracking back as it always has.
 */
class FixCeilingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FixCeilingAlarm.ACTION_ALARM) return
        FixCeilingAlarm.acquireWakeLock(context)
        val command = Intent(context, LocationTrackingService::class.java).setAction(FixCeilingAlarm.ACTION_CHECK)
        val started = try {
            context.startService(command) != null
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            Log.status(TAG_CEILING, "ceiling alarm: tracking service not running")
            FixCeilingAlarm.releaseWakeLock()
        }
    }
}

/**
 * The service's half of a ceiling alarm. Re-reads whether anyone is being shared with,
 * runs the keep-alive tick (which starts the forced fix when due), waits for it, and moves
 * the alarm to the next check. The bridge wake lock is released at the end either way.
 */
internal fun LocationTrackingService.onFixCeilingAlarm() {
    serviceScope.launch {
        try {
            refreshFixCeiling()
            withContext(Dispatchers.Main.immediate) { fixCeilingAlarmTick() }?.join()
            // Not in finally: a service destroyed mid-check must not re-arm the alarm.
            withContext(Dispatchers.Main.immediate) { rearmFixCeiling() }
        } finally {
            FixCeilingAlarm.releaseWakeLock()
        }
    }
}

/** Main thread: the synchronous half of a ceiling alarm. Returns the forced fix in flight, if any. */
internal fun LocationTrackingService.fixCeilingAlarmTick(): Job? {
    keepAliveTick()
    return forcedFixJob?.takeIf { it.isActive }
}

/**
 * Re-evaluates whether the phone is sharing and arms or cancels the ceiling to match.
 * Called from every heartbeat (and pre-unlock publish) tick and from each ceiling alarm.
 */
// Broad catch is deliberate: a DAO failure here must not kill the heartbeat loop.
@Suppress("TooGenericExceptionCaught")
internal suspend fun LocationTrackingService.refreshFixCeiling() {
    val sharing = try {
        isSharingOut()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.status(TAG_CEILING, "sharing check failed; ceiling left as is", e)
        return
    }
    withContext(Dispatchers.Main.immediate) { updateFixCeiling(sharing) }
}

/**
 * True when a publish right now would reach someone: global sharing on and at least one
 * person with sending enabled or one live temporary link. Before first unlock only the
 * device-protected roster is readable, so that decides.
 */
internal suspend fun LocationTrackingService.isSharingOut(): Boolean {
    if (directBootJob?.isActive == true) return publishRoster.isNotEmpty()
    if (!LocationServiceController.isGlobalSharingEnabled(this)) return false
    val self = Networking.userid
    if (repository.getAllUsers().any { it.id != self && it.sendingEnabled }) return true
    val now = Clock.System.now()
    return repository.temporaryLinkStore.getAll().any { now < it.deleteAt }
}

/** Main thread. Turns the ceiling on (alarm armed) or off (alarm cancelled) when sharing changes. */
internal fun LocationTrackingService.updateFixCeiling(sharing: Boolean) {
    if (fixCeilingEnabled == sharing) return
    fixCeilingEnabled = sharing
    Log.status(TAG_CEILING, if (sharing) "sharing: 15-min check ceiling on" else "not sharing: ceiling off")
    rearmFixCeiling()
}

/** Main thread. Moves the alarm to the next due check, or cancels it while the ceiling is off. */
internal fun LocationTrackingService.rearmFixCeiling() {
    if (fixCeilingEnabled) {
        FixCeilingAlarm.arm(this, SystemClock.elapsedRealtime(), lastForcedFixAttemptMs)
    } else {
        FixCeilingAlarm.cancel(this)
    }
}

/** The forced fix's own partial wake lock, created once per service. */
internal fun LocationTrackingService.forcedFixWakeLock(): PowerManager.WakeLock? =
    forcedFixWakeLockHolder ?: getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findfamily:forced-fix")
        ?.apply { setReferenceCounted(false) }
        ?.also { forcedFixWakeLockHolder = it }

/**
 * Publishes the held fix now through the normal path: the heartbeat after unlock, the
 * device-protected publish before it. Both stamp the publish with the current time.
 */
internal suspend fun LocationTrackingService.publishCheckedFix() {
    if (directBootJob?.isActive == true) publishDirectBoot(publishRoster) else syncHeartbeat()
}
