package com.vayunmathur.email.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutboxSendWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After reboot or app update:
 * - Purges legacy 15-min full poll, re-schedules hourly non-INBOX poll.
 * - Runs a one-off catch-up for folder discovery + non-INBOX.
 * - Restarts outbox retry chain and IDLE push for INBOX (no-op if no accounts).
 *
 * On Android 12+ (S) starting a FGS with type dataSync from BOOT_COMPLETED is
 * NOT allowed (ForegroundServiceStartNotAllowedException). We must not call
 * startForegroundService from boot on S+. Instead we schedule safe WorkManager
 * work and set a flag `idle_restart_pending` so [ImapIdleService] is started
 * when the app next comes to foreground (MainActivity.onStart) or via
 * [com.vayunmathur.email.data.ImapIdleRetryWorker].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val pending = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        Log.debug(TAG, "Boot (${intent.action}): restarting hourly + one-off + outbox")
                        EmailSyncWorker.scheduleHourlyNonInboxSync(appContext)
                        EmailSyncWorker.runOneOffSync(appContext)
                        OutboxSendWorker.runNow(appContext)

                        // Do NOT start the FGS from boot — it would crash with
                        // ForegroundServiceStartNotAllowedException for dataSync.
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit { putBoolean(KEY_PENDING, true) }
                        Log.debug(TAG, "Boot: deferred IDLE start, set $KEY_PENDING=true")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        // WorkManager scheduling / deferred-start writes are
                        // best-effort at boot; none may crash the process
                        // (#939: Security/IllegalState were only the known
                        // foreground-service cases).
                        Log.status(TAG, "BootReceiver failed: ${t.message}", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    /** Holds preference keys used to restart IMAP IDLE after boot. */
    companion object {
        private const val TAG = "EmailBoot"
        const val PREFS = "email_idle_state"
        const val KEY_PENDING = "idle_restart_pending"
    }
}
