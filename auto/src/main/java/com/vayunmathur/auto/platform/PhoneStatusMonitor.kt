package com.vayunmathur.auto.platform

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.notifications.MessageMirrorService

/**
 * The phone's own status for the rail status cluster (`rail_statusbar`):
 * cell signal bars, battery level + charging, do-not-disturb, and the
 * message notification badge count. Gearhead's `RailStatusBarFragment` reads
 * the same phone-side sources; the phone cannot know the car's radio state,
 * so nothing is faked -- absent sources stay gone, like the layout's
 * `gone` icon row.
 *
 * All reads are best-effort and fail-closed: no READ_PHONE_STATE grant means
 * no signal (the icon stays gone), no listener binding means no badge count.
 * Main thread only; the car cluster pulls the latest snapshot on its 1Hz
 * tick rather than observing a flow (the cluster lives on a private
 * Presentation with no lifecycle owner for collectors).
 */
class PhoneStatusMonitor(private val context: Context) {

    /** Latest snapshot; read on the main thread by the car status cluster. */
    @Volatile
    var snapshot: PhoneStatus = PhoneStatus()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var telephony: TelephonyManager? = null
    private var signalListener: PhoneStateListener? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        // Signal bars 0-4; needs READ_PHONE_STATE, otherwise stays gone.
        runCatching {
            val manager = context.getSystemService(TelephonyManager::class.java) ?: return@runCatching
            telephony = manager
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(strength: SignalStrength?) {
                    val level = strength?.level ?: return
                    snapshot = snapshot.copy(signalBars = level.coerceIn(0, 4))
                }
            }
            signalListener = listener
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }.onFailure { Log.status(TAG, "signal listener unavailable", it) }
        // Battery level + charging: sticky broadcast, no permission needed.
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    if (level < 0 || scale <= 0) return
                    snapshot = snapshot.copy(
                        batteryPercent = (level * 100 / scale).coerceIn(0, 100),
                        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL,
                    )
                }
            }
            batteryReceiver = receiver
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.status(TAG, "battery receiver unavailable", it) }
        refreshDndAndBadge()
    }

    /**
     * Refreshes the DND + badge snapshot. Called on start and on the car
     * cluster tick (cheap reads; the badge counts our own listener's active
     * message notifications, the DND reads the interruption filter).
     */
    fun refreshDndAndBadge() {
        val dnd = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return@runCatching false
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        val badge = runCatching { MessageMirrorService.activeMessageCount() }.getOrDefault(0)
        snapshot = snapshot.copy(doNotDisturb = dnd, notificationCount = badge)
    }

    fun stop() {
        started = false
        signalListener?.let { listener ->
            runCatching {
                @Suppress("DEPRECATION")
                telephony?.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }
        signalListener = null
        telephony = null
        batteryReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        batteryReceiver = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val TAG = "MaAuto.PhoneStatus"
    }
}

/**
 * Phone-side status for the rail cluster. `signalBars` 0-4, null when the
 * signal source is unavailable (icon stays gone); `batteryPercent` 0-100,
 * null until the first sticky broadcast; `notificationCount` 0 hides the
 * badge (the layout's `gone` default).
 */
data class PhoneStatus(
    val signalBars: Int? = null,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean = false,
    val doNotDisturb: Boolean = false,
    val notificationCount: Int = 0,
)
