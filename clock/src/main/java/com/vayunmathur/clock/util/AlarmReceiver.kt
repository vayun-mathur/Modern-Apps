package com.vayunmathur.clock.util
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vayunmathur.clock.ui.AlarmActivity
import com.vayunmathur.clock.R

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra("ALARM_ID", -1L)

        // 1. Create the Intent for your "Ringing" Activity
        val ringIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALARM_ID", alarmId)
        }

        // 2. Wrap it in a PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Build the Notification
        val builder = NotificationCompat.Builder(context, "ALARM_CHANNEL_ID")
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentTitle(context.getString(R.string.label_alarm))
            .setContentText(context.getString(R.string.alarm_notification_wake_up))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // This is the key: it launches the activity automatically if the phone is locked
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)

        val db = context.buildDatabase<ClockDatabase>()
        CoroutineScope(Dispatchers.IO).launch {
            val alarm = db.alarmDao().get(alarmId)
            if(alarm.days == 0) {
                db.alarmDao().upsert(alarm.copy(enabled = false))
            } else {
                val alarmScheduler = AlarmScheduler.get()
                alarmScheduler.schedule(context, alarm)
            }
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(alarmId.toInt(), builder.build())
    }
}