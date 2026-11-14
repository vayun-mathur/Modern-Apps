package com.vayunmathur.email.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vayunmathur.library.log.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.imapServer
import com.vayunmathur.email.platform.loginUser
import com.vayunmathur.email.platform.resolveAuth
import com.vayunmathur.email.platform.smtpServer
import com.vayunmathur.email.ui.composer.InlineAttachment
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** WorkManager worker that attempts delivery of queued outbox messages. */
class OutboxSendWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = EmailRepository.get(applicationContext)
        val pending = repository.getOutbox()

        if (pending.isEmpty()) {
            Log.debug(TAG, "Outbox empty; nothing to flush")
            return Result.success()
        }

        val accounts = repository.getAccounts().associateBy { it.email }
        val manager = EmailManager()
        val now = System.currentTimeMillis()

        var anyFailed = false
        var soonestFutureMs = Long.MAX_VALUE
        for (entry in pending) {
            val outcome = processEntry(repository, manager, accounts, entry, now)
            anyFailed = anyFailed || outcome.failed
            soonestFutureMs = minOf(soonestFutureMs, outcome.deferMs)
        }

        maybeReschedule(repository, anyFailed, soonestFutureMs)
        return Result.success()
    }

    private data class EntryOutcome(val failed: Boolean, val deferMs: Long)

    private suspend fun processEntry(
        repository: EmailRepository,
        manager: EmailManager,
        accounts: Map<String, EmailAccount>,
        entry: OutboxEntry,
        now: Long,
    ): EntryOutcome {
        if (entry.scheduledAt > now) {
            return EntryOutcome(false, entry.scheduledAt - now)
        }
        val account = accounts[entry.accountEmail]
        if (account == null) {
            repository.updateOutboxAttempt(
                id = entry.id,
                error = "No account ${entry.accountEmail} found locally",
                attempts = entry.attemptCount + 1,
                at = now,
            )
            return EntryOutcome(true, Long.MAX_VALUE)
        }
        val uris = decodePaths(entry.attachmentLocalPaths).map { Uri.fromFile(File(it)) }
        val inline = decodeInline(entry.inlineImageJson).map {
            InlineAttachment(cid = it.cid, uri = Uri.fromFile(File(it.path)), mimeType = it.mime, fileName = it.name)
        }
        return when (val sendResult = trySend(manager, account, entry, uris, inline)) {
            is SendResult.Success -> {
                Log.dev(TAG, "Sent outbox entry #${entry.id} to ${entry.to}")
                attachmentDirFor(applicationContext, entry.id).deleteRecursively()
                repository.deleteOutboxEntry(entry)
                EntryOutcome(false, Long.MAX_VALUE)
            }
            is SendResult.Failure -> {
                Log.status(TAG, "Failed to send outbox entry #${entry.id}: ${sendResult.message}", sendResult.cause)
                repository.updateOutboxAttempt(
                    id = entry.id,
                    error = sendResult.message,
                    attempts = entry.attemptCount + 1,
                    at = now,
                )
                EntryOutcome(true, Long.MAX_VALUE)
            }
        }
    }

    private suspend fun maybeReschedule(
        repository: EmailRepository,
        anyFailed: Boolean,
        soonestFutureMs: Long,
    ) {
        val retryDue = anyFailed && repository.getOutboxCount() > 0
        if (!retryDue && soonestFutureMs == Long.MAX_VALUE) return
        val delayMs = if (!retryDue) {
            soonestFutureMs
        } else {
            minOf(soonestFutureMs, RETRY_INTERVAL_MINUTES * 60_000L)
        }.coerceAtLeast(MIN_RESCHEDULE_DELAY_MS)
        scheduleNext(applicationContext, delay = delayMs, unit = TimeUnit.MILLISECONDS)
    }

    private sealed class SendResult {
        object Success : SendResult()
        data class Failure(val message: String, val cause: Throwable?) : SendResult()
    }

    private suspend fun trySend(
        manager: EmailManager,
        account: EmailAccount,
        entry: OutboxEntry,
        uris: List<android.net.Uri>,
        inline: List<InlineAttachment>,
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
            inlineImages = inline,
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

    /** Holds WorkManager names and retry constants for outbox delivery. */
    companion object {
        private const val TAG = "OutboxSender"
        const val WORK_NAME = "OutboxSendWorker"
        const val RETRY_INTERVAL_MINUTES = 5L
        internal const val MIN_RESCHEDULE_DELAY_MS = 1_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Serializable
        /** A serializable inline-image reference stored with an outbox entry. */
        data class InlineJsonEntry(val cid: String, val path: String, val mime: String, val name: String)

        fun encodePaths(paths: List<String>): String = json.encodeToString(paths)
        fun decodePaths(encoded: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(encoded) }.getOrDefault(emptyList())

        fun encodeInline(entries: List<InlineJsonEntry>): String = json.encodeToString(entries)
        fun decodeInline(encoded: String): List<InlineJsonEntry> =
            runCatching { json.decodeFromString<List<InlineJsonEntry>>(encoded) }.getOrDefault(emptyList())

        fun attachmentDirFor(context: Context, entryId: Long): File =
            File(context.filesDir, "outbox/$entryId")

        fun inlineDirFor(context: Context, entryId: Long): File =
            File(context.filesDir, "outbox/$entryId/inline")

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<OutboxSendWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

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

/** Enqueues outbox messages for background SMTP delivery. */
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
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        initialError: String? = null,
        scheduledAt: Long = 0,
        isHtml: Boolean = false,
    ): OutboxEntry {
        val dao = EmailRepository.get(context).getDatabase().outboxDao()
        val base = OutboxEntry(
            accountEmail = accountEmail,
            to = to,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            attachmentLocalPaths = "[]",
            inlineImageJson = "[]",
            inReplyTo = inReplyTo,
            references = references,
            lastError = initialError,
            scheduledAt = scheduledAt,
            isHtml = isHtml,
        )
        val pendingId = dao.insertOutboxEntry(base)
        val localPaths = copyAttachmentsToOutbox(context, pendingId, attachments)
        val inlineEntries = copyInlineToOutbox(context, pendingId, inlineImages)
        val updated = base.copy(
            id = pendingId,
            attachmentLocalPaths = OutboxSendWorker.encodePaths(localPaths),
            inlineImageJson = OutboxSendWorker.encodeInline(inlineEntries),
        )
        dao.insertOutboxEntry(updated)
        if (scheduledAt > System.currentTimeMillis()) {
            OutboxSendWorker.scheduleNext(
                context,
                delay = (scheduledAt - System.currentTimeMillis())
                    .coerceAtLeast(OutboxSendWorker.MIN_RESCHEDULE_DELAY_MS),
                unit = java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        } else {
            OutboxSendWorker.runNow(context)
        }
        return updated
    }

    suspend fun delete(context: Context, entry: OutboxEntry) {
        val dao = EmailRepository.get(context).getDatabase().outboxDao()
        OutboxSendWorker.attachmentDirFor(context, entry.id).deleteRecursively()
        dao.deleteOutboxEntry(entry)
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
                } ?: copyFromFilePath(uri, outFile)
                if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
            } catch (e: IOException) {
                Log.status("OutboxManager", "Could not copy attachment $uri", e)
                null
            }
        }
    }

    private fun copyInlineToOutbox(
        context: Context,
        entryId: Long,
        inline: List<InlineAttachment>,
    ): List<OutboxSendWorker.Companion.InlineJsonEntry> {
        if (inline.isEmpty()) return emptyList()
        val dir = OutboxSendWorker.inlineDirFor(context, entryId)
        dir.mkdirs()
        return inline.mapIndexedNotNull { index, att ->
            try {
                val safeName = att.fileName.replace(Regex("[/\\\\]"), "_").ifBlank { "inline-$index.jpg" }
                val outFile = File(dir, "${System.currentTimeMillis()}-$index-$safeName")
                try {
                    context.contentResolver.openInputStream(att.uri)?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: copyFromFilePath(att.uri, outFile)
                } catch (_: Exception) {
                    copyFromFilePath(att.uri, outFile)
                }
                if (outFile.exists() && outFile.length() > 0) {
                    OutboxSendWorker.Companion.InlineJsonEntry(
                        cid = att.cid,
                        path = outFile.absolutePath,
                        mime = att.mimeType,
                        name = att.fileName,
                    )
                } else null
            } catch (e: IOException) {
                Log.status("OutboxManager", "Could not copy inline $att", e)
                null
            }
        }
    }

    private fun copyFromFilePath(uri: Uri, outFile: File) {
        val path = uri.path ?: return
        val src = File(path)
        if (src.exists()) src.inputStream().use { input -> outFile.outputStream().use { out -> input.copyTo(out) } }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0).takeIf { !it.isNullOrBlank() } else null
            }
    }
}
