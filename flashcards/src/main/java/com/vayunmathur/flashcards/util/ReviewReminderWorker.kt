package com.vayunmathur.flashcards.util

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.flashcards.MainActivity
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.getAllCardsImpl
import com.vayunmathur.library.util.ensureNotificationChannel
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Schedules/cancels the daily "cards are due" reminder via WorkManager. */
object ReviewReminder {
    private const val WORK_NAME = "flashcards_review_reminder"
    const val CHANNEL_ID = "review_reminders"

    fun update(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMinutes(hour, minute), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Minutes from now until the next occurrence of [hour]:[minute] (local). */
    private fun initialDelayMinutes(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return ((next.timeInMillis - now.timeInMillis) / MILLIS_PER_MINUTE).coerceAtLeast(1)
    }

    /** Milliseconds in a minute. */
    private const val MILLIS_PER_MINUTE = 60_000L
}

/** Posts a notification when cards are due for review. */
class ReviewReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val repository = FlashcardsRepository.get(applicationContext)
        val cards = repository.getAllCardsImpl()
        val due = cards.count {
            !it.isSuspended && ((it.state != CardState.NEW && it.dueDate <= now) || it.state == CardState.NEW)
        }
        if (due > 0) notify(due)
        return Result.success()
    }

    private fun notify(due: Int) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.ensureNotificationChannel(
            id = ReviewReminder.CHANNEL_ID,
            name = context.getString(R.string.reminder_channel_name),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            description = context.getString(R.string.reminder_channel_description),
        )
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReviewReminder.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.resources.getQuantityString(R.plurals.reminder_text, due, due))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val REMINDER_NOTIFICATION_ID = 4201
    }
}
