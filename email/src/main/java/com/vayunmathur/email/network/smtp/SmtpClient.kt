package com.vayunmathur.email.network.smtp

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.email.ui.composer.InlineAttachment
import com.vayunmathur.email.network.imap.TrustAll
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level SMTP client — app-password primary + Outlook OAuth XOAUTH2 support.
 */
object SmtpClient {

    private const val TAG = "SmtpClient"

    suspend fun sendMessage(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        from: String? = null,
        asHtml: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val fromAddress = from ?: user
        val mimeString = MimeBuilder.buildMessage(
            context = context,
            from = fromAddress,
            to = to,
            subject = subject,
            body = body,
            asHtml = asHtml,
            cc = cc,
            attachments = attachments,
            inlineImages = inlineImages,
            inReplyTo = inReplyTo,
            references = references,
        )
        val useTrustAll = !TrustAll.isKnownHost(server.host)
        val conn = RawSmtpConnection(server, trustAll = useTrustAll)
        try {
            openSession(conn, server)
            authenticate(conn, user, auth)
            submitMessage(conn, fromAddress, to, cc, bcc, mimeString)
            conn.quit()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun openSession(conn: RawSmtpConnection, server: ServerConfig) {
        conn.connect()
        conn.ehlo("email.local")
        if (!server.useSsl && conn.hasCap("STARTTLS")) {
            conn.startTls(server.host)
            conn.ehlo("email.local")
        }
    }

    private fun authenticate(conn: RawSmtpConnection, user: String, auth: EmailManager.AuthType) {
        when (auth) {
            is EmailManager.AuthType.OAuth -> authenticateOauth(conn, user, auth.token)
            is EmailManager.AuthType.Password -> authenticatePassword(conn, user, auth.value)
        }
    }

    private fun authenticateOauth(conn: RawSmtpConnection, user: String, token: String) {
        try {
            conn.authXoauth2(user, token)
        } catch (e: IOException) {
            Log.error(TAG, "XOAUTH2 SMTP failed", e)
            throw e
        }
    }

    private fun authenticatePassword(conn: RawSmtpConnection, user: String, password: String) {
        if (conn.hasCap("AUTH=PLAIN") || conn.hasCap("PLAIN")) {
            authenticatePlainWithLoginFallback(conn, user, password)
        } else if (conn.hasCap("LOGIN")) {
            conn.authLogin(user, password)
        } else {
            authenticatePlainWithLoginFallback(conn, user, password)
        }
    }

    private fun authenticatePlainWithLoginFallback(conn: RawSmtpConnection, user: String, password: String) {
        try {
            conn.authPlain(user, password)
        } catch (e: IOException) {
            if (conn.hasCap("LOGIN")) conn.authLogin(user, password) else throw e
        }
    }

    private fun submitMessage(
        conn: RawSmtpConnection,
        fromAddress: String,
        to: String,
        cc: String?,
        bcc: String?,
        mimeString: String,
    ) {
        conn.mailFrom(fromAddress)
        for (rcpt in collectRecipients(to, cc, bcc)) {
            conn.rcptTo(rcpt)
        }
        conn.data(mimeString)
    }

    private fun collectRecipients(to: String, cc: String?, bcc: String?): List<String> {
        val allRecipients = mutableListOf<String>()
        allRecipients.addAll(splitAddresses(to))
        cc?.takeIf { it.isNotBlank() }?.let { allRecipients.addAll(splitAddresses(it)) }
        bcc?.takeIf { it.isNotBlank() }?.let { allRecipients.addAll(splitAddresses(it)) }
        return allRecipients.distinct()
    }

    private fun splitAddresses(input: String): List<String> =
        input.split(',', ';').map { it.trim() }.filter { it.isNotBlank() && it.contains('@') }.map {
            Regex("<([^>]+)>").find(it)?.groupValues?.get(1)?.trim() ?: it.trim()
        }
}
