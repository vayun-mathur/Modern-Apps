package com.vayunmathur.email.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutboxSendWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After reboot or app update:
 * - Purges legacy 15-min full poll, re-schedules hourly non-INBOX poll.
 * - Runs a one-off catch-up for folder discovery + non-INBOX.
 * - Restarts outbox retry chain and IDLE push for INBOX (no-op if no accounts).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val pending = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.d(TAG, "Boot (${intent.action}): restarting IDLE + hourly non-INBOX")
                        EmailSyncWorker.scheduleHourlyNonInboxSync(appContext)
                        EmailSyncWorker.runOneOffSync(appContext)
                        OutboxSendWorker.runNow(appContext)
                        ImapIdleService.start(appContext)
                    } catch (t: Throwable) {
                        Log.w(TAG, "BootReceiver failed: ${t.message}", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "EmailBoot"
    }
}
