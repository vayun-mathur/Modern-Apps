package com.vayunmathur.email.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.OutboxEntry
import com.vayunmathur.email.loginUser
import com.vayunmathur.email.resolveAuth
import com.vayunmathur.email.smtpServer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Worker that flushes [OutboxEntry] rows. Re-runs itself every
 * [RETRY_INTERVAL_MINUTES] minutes while the outbox is non-empty.
 *
 * `PeriodicWorkRequest` has a 15-minute minimum interval; we want 5 minutes,
 * so we chain `OneTimeWorkRequest`s instead. A successful flush stops the
 * chain — there's no need to wake up if there's nothing to send.
 */
class OutboxSendWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = EmailDatabase.getInstance(applicationContext)
        val dao = db.emailDao()
        val pending = dao.getOutbox()

        if (pending.isEmpty()) {
            Log.d(TAG, "Outbox empty; nothing to flush")
            return Result.success()
        }

        val accounts = dao.getAccounts().associateBy { it.email }
        val manager = EmailManager()
        var anyFailed = false
        var soonestFutureMs = Long.MAX_VALUE
        val now = System.currentTimeMillis()

        for (entry in pending) {
            // Scheduled-send: not due yet — leave it queued and remember when to wake.
            if (entry.scheduledAt > now) {
                soonestFutureMs = minOf(soonestFutureMs, entry.scheduledAt - now)
                continue
            }
            val account = accounts[entry.accountEmail]
            if (account == null) {
                anyFailed = true
                dao.updateOutboxAttempt(
                    id = entry.id,
                    error = "No account ${entry.accountEmail} found locally",
                    attempts = entry.attemptCount + 1,
                    at = now,
                )
                continue
            }
            val uris = decodePaths(entry.attachmentLocalPaths).map { Uri.fromFile(File(it)) }
            when (val sendResult = trySend(manager, account, entry, uris)) {
                is SendResult.Success -> {
                    Log.d(TAG, "Sent outbox entry #${entry.id} to ${entry.to}")
                    attachmentDirFor(applicationContext, entry.id).deleteRecursively()
                    dao.deleteOutboxEntry(entry)
                }
                is SendResult.Failure -> {
                    anyFailed = true
                    Log.w(TAG, "Failed to send outbox entry #${entry.id}: ${sendResult.message}", sendResult.cause)
                    dao.updateOutboxAttempt(
                        id = entry.id,
                        error = sendResult.message,
                        attempts = entry.attemptCount + 1,
                        at = now,
                    )
                }
            }
        }

        // Reschedule if there's failed work to retry or a scheduled message still pending.
        val retryDue = anyFailed && dao.getOutboxCount() > 0
        if (retryDue || soonestFutureMs != Long.MAX_VALUE) {
            val delayMs = when {
                soonestFutureMs == Long.MAX_VALUE -> RETRY_INTERVAL_MINUTES * 60_000L
                retryDue -> minOf(soonestFutureMs, RETRY_INTERVAL_MINUTES * 60_000L)
                else -> soonestFutureMs
            }.coerceAtLeast(1_000L)
            scheduleNext(applicationContext, delay = delayMs, unit = TimeUnit.MILLISECONDS)
        }
        return Result.success()
    }

    private sealed class SendResult {
        object Success : SendResult()
        data class Failure(val message: String, val cause: Throwable?) : SendResult()
    }

    /**
     * Send the entry once. Returns either Success or Failure with the
     * underlying error message.
     */
    private suspend fun trySend(
        manager: EmailManager,
        account: com.vayunmathur.email.EmailAccount,
        entry: com.vayunmathur.email.OutboxEntry,
        uris: List<android.net.Uri>,
    ): SendResult = runCatching {
        manager.sendMessage(
            context = applicationContext,
            server = account.smtpServer(),
            user = account.loginUser(),
            auth = account.resolveAuth(applicationContext),
            to = entry.to,
            subject = entry.subject,
            body = entry.body,
            cc = entry.cc,
            bcc = entry.bcc,
            attachments = uris,
            inReplyTo = entry.inReplyTo,
            references = entry.references,
            asHtml = entry.isHtml,
            from = account.email,
        )
    }.fold(
        onSuccess = { SendResult.Success },
        onFailure = { SendResult.Failure(formatError(it), it) },
    )

    private fun formatError(t: Throwable?): String {
        if (t == null) return "Unknown error"
        val msg = t.message ?: t::class.simpleName ?: "Unknown error"
        return "${t.javaClass.simpleName}: $msg"
    }

    companion object {
        private const val TAG = "OutboxSender"
        const val WORK_NAME = "OutboxSendWorker"
        const val RETRY_INTERVAL_MINUTES = 5L

        private val json = Json { ignoreUnknownKeys = true }

        fun encodePaths(paths: List<String>): String = json.encodeToString(paths)
        fun decodePaths(encoded: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(encoded) }.getOrDefault(emptyList())

        fun attachmentDirFor(context: Context, entryId: Long): File =
            File(context.filesDir, "outbox/$entryId")

        /** Run immediately, cancelling any pending delayed retry. */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<OutboxSendWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        /** Run after `delay` units. Replaces any existing schedule. */
        fun scheduleNext(context: Context, delay: Long, unit: TimeUnit) {
            val req = OneTimeWorkRequestBuilder<OutboxSendWorker>()
                .setInitialDelay(delay, unit)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}

/**
 * Helper for enqueueing outgoing messages and managing their on-disk attachment
 * copies. Call [enqueue] from the UI/ViewModel layer.
 */
object OutboxManager {

    suspend fun enqueue(
        context: Context,
        accountEmail: String,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        initialError: String? = null,
        scheduledAt: Long = 0,
        isHtml: Boolean = false,
    ): OutboxEntry {
        val dao = EmailDatabase.getInstance(context).emailDao()
        // First insert to get an autogenerated id; we'll then copy attachments
        // into a dir named after that id and update the row with their paths.
        val base = OutboxEntry(
            accountEmail = accountEmail,
            to = to,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            attachmentLocalPaths = "[]",
            inReplyTo = inReplyTo,
            references = references,
            lastError = initialError,
            scheduledAt = scheduledAt,
            isHtml = isHtml,
        )
        val pendingId = dao.insertOutboxEntry(base)
        val localPaths = copyAttachmentsToOutbox(context, pendingId, attachments)
        val updated = base.copy(
            id = pendingId,
            attachmentLocalPaths = OutboxSendWorker.encodePaths(localPaths),
        )
        dao.insertOutboxEntry(updated)
        // Send now, or wake the worker at the scheduled time.
        if (scheduledAt > System.currentTimeMillis()) {
            OutboxSendWorker.scheduleNext(
                context,
                delay = (scheduledAt - System.currentTimeMillis()).coerceAtLeast(1_000L),
                unit = java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        } else {
            OutboxSendWorker.runNow(context)
        }
        return updated
    }

    suspend fun delete(context: Context, entry: OutboxEntry) {
        val dao = EmailDatabase.getInstance(context).emailDao()
        OutboxSendWorker.attachmentDirFor(context, entry.id).deleteRecursively()
        dao.deleteOutboxEntry(entry)
        // If we just emptied the outbox, no need to keep the retry chain alive.
        if (dao.getOutboxCount() == 0) {
            OutboxSendWorker.cancel(context)
        }
    }

    private fun copyAttachmentsToOutbox(
        context: Context,
        entryId: Long,
        attachments: List<Uri>,
    ): List<String> {
        if (attachments.isEmpty()) return emptyList()
        val dir = OutboxSendWorker.attachmentDirFor(context, entryId)
        dir.mkdirs()
        return attachments.mapIndexedNotNull { index, uri ->
            try {
                val displayName = queryDisplayName(context, uri) ?: "attachment-$index"
                val outFile = File(dir, "${System.currentTimeMillis()}-$index-$displayName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
            } catch (e: Exception) {
                Log.w("OutboxManager", "Could not copy attachment $uri", e)
                null
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0).takeIf { !it.isNullOrBlank() } else null
            }
    }
}
