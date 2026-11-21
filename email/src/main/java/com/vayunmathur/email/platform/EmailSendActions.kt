package com.vayunmathur.email.platform

import android.content.Context
import android.net.Uri
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.data.OutboxManager
import com.vayunmathur.email.data.OutboxSendWorker
import com.vayunmathur.email.network.smtp.MimeBuilder
import com.vayunmathur.email.ui.composer.InlineAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Compose/send/schedule/outbox operations extracted from [EmailViewModel]. */
class EmailSendActions(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val emailManager: EmailManager,
) {
    fun sendFrom(
        account: EmailAccount,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        asHtml: Boolean = false,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            // Mint the Message-ID once, before the first attempt: if the
            // direct send times out after DATA and we fall back to the
            // outbox, the worker retry must carry this same ID.
            val messageId = MimeBuilder.generateMessageId(account.email)
            try {
                emailManager.sendMessage(
                    context = appContext,
                    server = account.smtpServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    request = SendRequest(
                        to = to,
                        subject = subject,
                        body = body,
                        cc = cc,
                        bcc = bcc,
                        attachments = attachments,
                        inlineImages = inlineImages,
                        inReplyTo = inReplyTo,
                        references = references,
                        from = account.email,
                        asHtml = asHtml,
                        messageId = messageId,
                    ),
                )
                onSuccess()
            } catch (ignored: Exception) {
                val msg = ignored.message ?: ignored.javaClass.simpleName ?: "Unknown error"
                try {
                    OutboxManager.enqueue(
                        context = appContext,
                        accountEmail = account.email,
                        to = to,
                        subject = subject,
                        body = body,
                        cc = cc,
                        bcc = bcc,
                        attachments = attachments,
                        inlineImages = inlineImages,
                        inReplyTo = inReplyTo,
                        references = references,
                        initialError = msg,
                        isHtml = asHtml,
                        messageId = messageId,
                    )
                } catch (_: Exception) {
                    onError("$msg (and outbox save failed)")
                    return@launch
                }
                onError(msg)
            }
        }
    }

    /** Queue a message to send at [scheduledAt] (epoch millis) via the outbox. */
    fun schedule(
        account: EmailAccount,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        scheduledAt: Long,
        asHtml: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            OutboxManager.enqueue(
                context = appContext,
                accountEmail = account.email,
                to = to, subject = subject, body = body,
                cc = cc, bcc = bcc, attachments = attachments,
                inlineImages = inlineImages,
                inReplyTo = inReplyTo, references = references,
                scheduledAt = scheduledAt,
                isHtml = asHtml,
            )
            onDone()
        }
    }

    fun deleteOutboxEntry(entry: OutboxEntry) {
        scope.launch {
            OutboxManager.delete(appContext, entry)
        }
    }

    fun sendOutboxNow(context: Context) {
        OutboxSendWorker.runNow(context)
    }
}
