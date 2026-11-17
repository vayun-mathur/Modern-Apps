package com.vayunmathur.findfamily.notifications

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.vayunmathur.findfamily.R

/**
 * Per-person, per-event notification channels for arrivals, departures and no-shows
 * (issue #618).
 *
 * Each tracked person gets their own channel group containing an "Arrivals", a "Departures"
 * and a "No-show alerts" channel. Because Android exposes sound, vibration, importance and
 * Do-Not-Disturb override as per-channel settings, giving every person/event its own channel
 * lets the user tune each one independently from the system notification settings — no custom
 * pickers required.
 *
 * Channel creation is idempotent: re-creating a channel refreshes its name/description/group but
 * leaves any sound/vibration/DND choices the user has made intact.
 */
object FindFamilyNotificationChannels {

    fun entryExitChannelId(userId: Long, arrival: Boolean): String =
        "entry_exit_${userId}_${if (arrival) "arrival" else "departure"}"

    fun noShowChannelId(userId: Long): String = "no_show_${userId}"

    private fun personGroupId(userId: Long): String = "person_$userId"

    /** Ensure the no-show channel for [userId] exists, grouped under [userName]. */
    fun ensureNoShowChannel(context: Context, userId: Long, userName: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(personGroupId(userId), userName)
        )
        val noShow = NotificationChannel(
            noShowChannelId(userId),
            context.getString(R.string.notification_channel_noshow_name, userName),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_noshow_desc, userName)
            group = personGroupId(userId)
        }
        manager.createNotificationChannel(noShow)
    }

    /** Ensure the arrival and departure channels for [userId] exist, grouped under [userName]. */
    fun ensureEntryExitChannels(context: Context, userId: Long, userName: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(personGroupId(userId), userName)
        )
        val arrival = NotificationChannel(
            entryExitChannelId(userId, arrival = true),
            context.getString(R.string.notification_channel_arrival_name, userName),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_arrival_desc, userName)
            group = personGroupId(userId)
        }
        val departure = NotificationChannel(
            entryExitChannelId(userId, arrival = false),
            context.getString(R.string.notification_channel_departure_name, userName),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_departure_desc, userName)
            group = personGroupId(userId)
        }
        manager.createNotificationChannels(listOf(arrival, departure))
    }

    /** Open the system settings screen for this person's no-show channel. */
    fun openNoShowChannelSettings(context: Context, userId: Long, userName: String) {
        ensureNoShowChannel(context, userId, userName)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, noShowChannelId(userId))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Open the system settings screen for this person's arrival or departure channel. */
    fun openChannelSettings(context: Context, userId: Long, userName: String, arrival: Boolean) {
        ensureEntryExitChannels(context, userId, userName)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, entryExitChannelId(userId, arrival))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
