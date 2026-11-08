package com.vayunmathur.findfamily.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.User
import androidx.core.app.NotificationCompat

internal fun LocationTrackingService.setupNotificationChannels() {
    // 1. Create the Channel (Required for API 26+)
    val channel = NotificationChannel(
        LocationTrackingService.CHANNEL_ID,
        getString(R.string.notification_channel_location_tracking_name),
        NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't "pop up" or make noise
    ).apply {
        description = getString(R.string.notification_channel_location_tracking_desc)
    }

    // 2. Battery Alerts Channel (High Importance for visibility)
    val batteryChannel = NotificationChannel(
        LocationTrackingService.BATTERY_CHANNEL_ID,
        getString(R.string.notification_channel_battery_name),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = getString(R.string.notification_channel_battery_desc)
    }

    // 3. Entry/Exit Channel
    val arrivalChannel = NotificationChannel(
        LocationTrackingService.ENTRY_EXIT_CHANNEL_ID,
        getString(R.string.notification_channel_entry_exit_name),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = getString(R.string.notification_channel_entry_exit_desc)
    }

    // 4. UWB Find Nearby (UWB) Request Channel
    val uwbChannel = NotificationChannel(
        LocationTrackingService.UWB_REQUEST_CHANNEL_ID,
        getString(R.string.notification_channel_uwb_request_name),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = getString(R.string.notification_channel_uwb_request_desc)
    }

    // Register all channels
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannels(listOf(channel, batteryChannel, arrivalChannel, uwbChannel))
}

internal fun LocationTrackingService.createNotification(): Notification {
    // Create an Intent to open the app when the notification is clicked
    val pendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE // Required for API 31+
    )

    // 3. Build the notification
    return NotificationCompat.Builder(this, LocationTrackingService.CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_tracking_title))
        .setContentText(getString(R.string.notification_tracking_text))
        .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this exists in your res/drawable
        .setOngoing(true) // Makes it persistent
        .setContentIntent(pendingIntent)
        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // API 31+ specific
        .build()
}

internal fun LocationTrackingService.createNotificationWithCategory(
    title: String,
    message: String,
    category: String,
    userId: Long,
) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val channelId = when (category) {
        "BATTERY_LOW" -> LocationTrackingService.BATTERY_CHANNEL_ID
        "ENTRY_EXIT" -> LocationTrackingService.ENTRY_EXIT_CHANNEL_ID
        else -> LocationTrackingService.CHANNEL_ID
    }

    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_launcher_foreground) // Consider using specific icons for battery/location
        .setAutoCancel(true)
        .build()

    // Stable per-(user, category) ID so repeat notifications replace rather than stack.
    val notificationId = "$userId::$category".hashCode()
    manager.notify(notificationId, notification)
}

/**
 * Post an arrival/departure notification on the person's own per-event channel (issue #618),
 * so its sound, vibration and DND behaviour can be tuned independently in system settings.
 */
internal fun LocationTrackingService.notifyEntryExit(user: User, message: String, arrival: Boolean) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    FindFamilyNotificationChannels.ensureEntryExitChannels(this, user.id, user.name)
    val channelId = FindFamilyNotificationChannels.entryExitChannelId(user.id, arrival)
    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle(user.name)
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setAutoCancel(true)
        .build()
    val event = if (arrival) "ARRIVAL" else "DEPARTURE"
    val notificationId = "${user.id}::ENTRY_EXIT::$event".hashCode()
    manager.notify(notificationId, notification)
}

/**
 * Post a no-show alert notification on the watched person's own per-person channel
 * (issue #702), mirroring the #618 arrival/departure pattern, so its sound, vibration
 * and DND behaviour can be tuned independently in system settings.
 *
 * The notify ID is stable per alert ("$alertId::NO_SHOW") so re-deliveries replace
 * rather than stack. Tapping opens the app at the watched person / expected place.
 */
internal fun LocationTrackingService.notifyNoShow(
    user: User,
    message: String,
    alertId: Long,
    waypointId: Long?,
) = notifyNoShowAlert(user, message, alertId, waypointId)

/**
 * Post a no-show notification from any [Context] (the tracking service above, or
 * [com.vayunmathur.findfamily.platform.NoShowCheckWorker], which is where due
 * alerts are actually claimed). Keeping this Context-based is what lets the
 * worker deliver the alert instead of only logging it.
 */
internal fun Context.notifyNoShowAlert(
    user: User,
    message: String,
    alertId: Long,
    waypointId: Long?,
) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    FindFamilyNotificationChannels.ensureNoShowChannel(this, user.id, user.name)
    val channelId = FindFamilyNotificationChannels.noShowChannelId(user.id)
    // Deep-link to the watched person / expected place: MainActivity routes
    // Route.MainPage(selectedUserId, selectedWaypointId) from these extras.
    val openIntent = Intent(this, MainActivity::class.java).apply {
        putExtra(EXTRA_NOSHOW_USER_ID, user.id)
        if (waypointId != null) putExtra(EXTRA_NOSHOW_WAYPOINT_ID, waypointId)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentIntent = PendingIntent.getActivity(
        this, ("noshow-$alertId").hashCode(), openIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle(user.name)
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .build()
    manager.notify("$alertId::NO_SHOW".hashCode(), notification)
}

/** Intent extras carrying the no-show deep-link target (watched person / expected place). */
internal const val EXTRA_NOSHOW_USER_ID =
    "com.vayunmathur.findfamily.EXTRA_NOSHOW_USER_ID"
internal const val EXTRA_NOSHOW_WAYPOINT_ID =
    "com.vayunmathur.findfamily.EXTRA_NOSHOW_WAYPOINT_ID"

/**
 * Notification fired when an incoming UWB Find Nearby (UWB) request arrives
 * via the heartbeat. Tapping it opens MainActivity with a deep link to the
 * ranging screen for the requesting user.
 */
internal fun LocationTrackingService.createUwbRequestNotification(senderName: String, senderId: Long) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val openIntent = Intent(this, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_UWB_PEER_ID, senderId)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pi = PendingIntent.getActivity(
        this, senderId.hashCode(), openIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val n = NotificationCompat.Builder(this, LocationTrackingService.UWB_REQUEST_CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_uwb_request_title))
        .setContentText(getString(R.string.notification_uwb_request_text, senderName))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .build()
    manager.notify("$senderId::UWB_REQUEST".hashCode(), n)
}
