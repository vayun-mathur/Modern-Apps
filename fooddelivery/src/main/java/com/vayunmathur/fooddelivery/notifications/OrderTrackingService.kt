package com.vayunmathur.fooddelivery.notifications

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.fooddelivery.api.BitesOrders
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.platform.AppInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the [OrderLiveUpdate] notification current while an order
 * is in flight, even with the app backgrounded. It re-reads the same `/orders/past/all`
 * list the tracking screen polls and re-finds the order, updating every [POLL_MS] until
 * the order is done or gone, then stops itself.
 *
 * Foreground type is `specialUse`: we poll an API on a delivery-length timescale, so
 * neither `location` (we read no device location) nor the time-limited `shortService` /
 * `dataSync` types fit.
 */
class OrderTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var trackedOrderId: Int = -1

    override fun onCreate() {
        super.onCreate()
        // This service can be (re)started by the system into a cold process where
        // MainActivity never runs (START_REDELIVER_INTENT after a kill). AppInit.start
        // is normally MainActivity's job, and pollLoop awaits it -- without this call
        // that await never completes and tracking hangs on the placeholder forever.
        // start() is idempotent, so the warm-process path is unaffected.
        AppInit.start(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val orderId = intent?.getIntExtra(EXTRA_ORDER_ID, -1) ?: -1
        if (orderId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Satisfy the startForeground deadline immediately with a placeholder; the first
        // poll replaces it with real data a moment later.
        try {
            startForegroundCompat(OrderLiveUpdate.build(this, Order(id = orderId)))
        } catch (e: SecurityException) {
            Log.error(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Idempotent: a repeat start for the same order just refreshes, no new loop.
        if (orderId == trackedOrderId && pollJob?.isActive == true) {
            return START_REDELIVER_INTENT
        }
        trackedOrderId = orderId
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop(orderId) }
        return START_REDELIVER_INTENT
    }

    private suspend fun pollLoop(orderId: Int) {
        // The saved auth token is restored by the background warm-up.
        AppInit.awaitReady()
        while (true) {
            val order = runCatching {
                BitesOrders.getOrders().firstOrNull { it.id == orderId }
            }.getOrNull()

            if (order == null) {
                Log.dev(TAG, "order $orderId not found; stopping")
                finish()
                return
            }

            Log.dev(TAG, "order $orderId stage=${order.stage} eta=${order.etaMillis}")
            notify(OrderLiveUpdate.build(this, order))

            if (order.isDone) {
                Log.dev(TAG, "order $orderId done; stopping")
                // Leave the terminal update visible briefly, then clear it.
                delay(TERMINAL_LINGER_MS)
                finish()
                return
            }
            delay(POLL_MS)
        }
    }

    private fun notify(notification: Notification) {
        // notify() needs POST_NOTIFICATIONS on API 33+; without it the foreground
        // placeholder simply stays put rather than crashing.
        try {
            NotificationManagerCompat.from(this).notify(OrderLiveUpdate.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.status(TAG, "notify skipped (no permission)", e)
        }
    }

    private fun finish() {
        OrderLiveUpdate.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OrderLiveUpdate.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(OrderLiveUpdate.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OrderTrackingService"

        /** Mirrors OrderTrackingScreen.POLL_MS. */
        private const val POLL_MS = 15_000L
        private const val TERMINAL_LINGER_MS = 5_000L
        private const val EXTRA_ORDER_ID = "order_id"

        fun start(context: Context, orderId: Int?) {
            if (orderId == null || orderId <= 0) return
            val intent = Intent(context, OrderTrackingService::class.java)
                .putExtra(EXTRA_ORDER_ID, orderId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderTrackingService::class.java))
        }
    }
}
