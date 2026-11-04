package com.vayunmathur.securemail.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.work.startRepeatedTask
import com.vayunmathur.securemail.data.Identities
import com.vayunmathur.securemail.data.InboundStore
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.network.MailSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.minutes

/**
 * Periodic backstop behind the live socket: while the app is backgrounded
 * without the foreground service, this worker ensures the shared socket loop
 * is running so backlog drains (SUB → MSG drain). It never stops the loop:
 * the loop is process-wide and service-owned; stopping it here would kill a
 * live service session mid-claim (this exact race showed "offline" right
 * after registering). No-ops without an identity or when already connected.
 */
class MailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = MailConfig(applicationContext).settings.first()
        if (settings.address.isBlank()) return Result.success()
        if (MailSocket.connected) return Result.success()
        return try {
            // Nudge the shared loop awake; the SUB drains backlog through the
            // same InboundStore path as the live service. No teardown after:
            // if the service is alive it keeps the session; if not, the loop
            // persists for the worker's lifetime and idles.
            MailSocket.start(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                identities = {
                    Identities.active(applicationContext)
                        .map { MailSocket.IdentitySnapshot(it.address, it.publicArmor) }
                },
                sign = { address, payload -> Identities.sign(applicationContext, address, payload) },
                onMessage = { msg -> InboundStore.store(applicationContext, msg) },
            )
            // Give the SUB + backlog drain time to complete within the worker.
            kotlinx.coroutines.delay(20_000)
            Result.success()
        } catch (e: Exception) {
            Log.status(TAG, "sync worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MailSyncWorker"
        private const val WORK_NAME = "securemail_sync"

        fun schedule(context: Context) {
            startRepeatedTask<MailSyncWorker>(context, WORK_NAME, 15.minutes)
        }
    }
}
