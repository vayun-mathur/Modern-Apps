package com.vayunmathur.shop.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.MaNotifications
import com.vayunmathur.library.work.startRepeatedTask
import com.vayunmathur.shop.MainActivity
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.domain.OrderTrackingUpdates
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopHttpBackend
import kotlin.time.Duration.Companion.hours

/** Polls the server for merchant order updates and keeps the background schedule in sync. */
object OrderTrackingSync {
    private const val WORK_NAME = "shop_order_tracking"
    private const val CHANNEL_ID = "delivery_updates"

    /** Returns the saved orders plus those whose delivery status changed. */
    suspend fun refresh(context: Context, backend: ShopBackend = ShopHttpBackend): OrderTrackingUpdates.Merge {
        val queries = OrderTrackingUpdates.queriesToPoll(OrderStore.getAll(context))
        if (queries.isEmpty()) return OrderTrackingUpdates.Merge(OrderStore.getAll(context), emptyList())
        val updates = backend.orderStatus(queries).getOrNull()
            ?: return OrderTrackingUpdates.Merge(OrderStore.getAll(context), emptyList())
        var changed = emptyList<PlacedOrder>()
        val saved = OrderStore.update(context) { current ->
            OrderTrackingUpdates.merge(current, updates).also { changed = it.changed }.orders
        }
        return OrderTrackingUpdates.Merge(saved, changed)
    }

    fun schedule(context: Context, orders: List<PlacedOrder>) {
        if (orders.any(OrderTrackingUpdates::needsTracking)) {
            startRepeatedTask<OrderTrackingWorker>(context, WORK_NAME, 1.hours)
        } else {
            cancel(context)
        }
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("${WORK_NAME}_periodic")
    }

    // Guarded below; the library worker uses the same suppression for the same lint gap.
    @SuppressLint("MissingPermission")
    fun notifyChanges(context: Context, changed: List<PlacedOrder>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (order in changed) {
            val tracking = order.tracking ?: continue
            val status = context.getString(trackingStatusRes(tracking.status))
            val label = tracking.label?.let { " $it" }.orEmpty()
            MaNotifications.post(
                context = context,
                channelId = CHANNEL_ID,
                channelName = context.getString(R.string.delivery_updates_channel),
                notificationId = order.trackingToken.hashCode(),
                launchIntent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_ORDERS),
            ) { builder ->
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(context.getString(R.string.order_update_title, order.displayName, label))
                    .setContentText(status)
                    .setAutoCancel(true)
            }
        }
    }
}

@StringRes
fun trackingStatusRes(status: String): Int = when (status) {
    "shipped" -> R.string.tracking_shipped
    "in_transit" -> R.string.tracking_in_transit
    "out_for_delivery" -> R.string.tracking_out_for_delivery
    "partially_delivered" -> R.string.tracking_partially_delivered
    "delivered" -> R.string.tracking_delivered
    "delivery_issue" -> R.string.tracking_delivery_issue
    "canceled" -> R.string.tracking_canceled
    else -> R.string.tracking_confirmed
}

class OrderTrackingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AppInit.start(applicationContext)
        AppInit.awaitReady()
        val result = OrderTrackingSync.refresh(applicationContext)
        OrderTrackingSync.notifyChanges(applicationContext, result.changed)
        // Never re-enqueue from here: the immediate request would chain forever.
        if (result.orders.none(OrderTrackingUpdates::needsTracking)) OrderTrackingSync.cancel(applicationContext)
        return Result.success()
    }
}
