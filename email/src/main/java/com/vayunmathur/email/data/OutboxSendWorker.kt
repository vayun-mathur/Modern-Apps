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
import com.vayunmathur.email.composer.InlineAttachment
import com.vayunmathur.email.loginUser
import com.vayunmathur.email.resolveAuth
import com.vayunmathur.email.smtpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

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
            val inline = decodeInline(entry.inlineImageJson).map {
                InlineAttachment(cid = it.cid, uri = Uri.fromFile(File(it.path)), mimeType = it.mime, fileName = it.name)
            }
            when (val sendResult = trySend(manager, account, entry, uris, inline)) {
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

    private suspend fun trySend(
        manager: EmailManager,
        account: com.vayunmathur.email.EmailAccount,
        entry: com.vayunmathur.email.OutboxEntry,
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

    companion object {
        private const val TAG = "OutboxSender"
        const val WORK_NAME = "OutboxSendWorker"
        const val RETRY_INTERVAL_MINUTES = 5L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Serializable
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
        val dao = EmailDatabase.getInstance(context).emailDao()
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
            } catch (e: Exception) {
                Log.w("OutboxManager", "Could not copy attachment $uri", e)
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
                    OutboxSendWorker.Companion.InlineJsonEntry(cid = att.cid, path = outFile.absolutePath, mime = att.mimeType, name = att.fileName)
                } else null
            } catch (e: Exception) {
                Log.w("OutboxManager", "Could not copy inline $att", e)
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
