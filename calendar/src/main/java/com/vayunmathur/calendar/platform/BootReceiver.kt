package com.vayunmathur.calendar.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.vayunmathur.library.log.Log
import androidx.glance.appwidget.updateAll
import com.vayunmathur.calendar.widget.CalendarGlanceWidget
import com.vayunmathur.calendar.widget.CalendarMonthGlanceWidget
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reschedules all event-reminder alarms after a reboot or app update, since
 * AlarmManager alarms don't survive either. Mirrors the clock app's
 * [com.vayunmathur.clock.util.BootReceiver] plus findfamily's
 * package-replaced handling.
 *
 * Runs twice on a reboot and does different work each time. At
 * ACTION_LOCKED_BOOT_COMPLETED the calendar provider is credential-encrypted and
 * unreadable, so alarms are re-armed from the device-protected
 * [com.vayunmathur.calendar.data.ReminderMirror]. At ACTION_BOOT_COMPLETED - which an
 * FBE device only delivers once the user has unlocked - the provider is authoritative
 * and a full reconcile runs, replacing whatever the locked pass armed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val unlocked =
                    context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
                val action = intent.action
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        if (unlocked) reconcileFromProvider(context, action)
                        else armFromMirror(context, action)
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
        }
    }

    private suspend fun armFromMirror(context: Context, action: String?) {
        try {
            val armed = ReminderScheduler.scheduleFromMirror(context)
            Log.status(TAG, "$action: armed $armed reminder(s) from the device-protected mirror")
        } catch (expected: Exception) {
            Log.error(TAG, "$action: could not arm reminders from the mirror", expected)
        }
        // Widgets are deliberately skipped: Glance keeps its state in credential-encrypted
        // storage, and the launcher does not render before the first unlock anyway.
    }

    private suspend fun reconcileFromProvider(context: Context, action: String?) {
        // Reminders and widgets are caught separately so a failure in one cannot silently
        // take out the other - a single blanket catch here is what hid the locked-boot bug.
        try {
            ReminderScheduler.reconcileAll(context)
        } catch (expected: Exception) {
            Log.error(TAG, "$action: could not reschedule reminders from the calendar provider", expected)
        }
        try {
            context.scheduleHourlyUpdate(CalendarGlanceWidget::class, requiresNetwork = false)
            context.scheduleHourlyUpdate(CalendarMonthGlanceWidget::class, requiresNetwork = false)
            CalendarGlanceWidget().updateAll(context)
            CalendarMonthGlanceWidget().updateAll(context)
        } catch (expected: Exception) {
            Log.error(TAG, "$action: could not refresh widgets", expected)
        }
    }

    private companion object {
        const val TAG = "CalendarBootReceiver"
    }
}
