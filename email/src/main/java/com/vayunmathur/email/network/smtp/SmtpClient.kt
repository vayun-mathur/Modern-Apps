package com.vayunmathur.email.network.smtp

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.SendRequest
import com.vayunmathur.email.platform.ServerConfig
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
        request: SendRequest,
    ) = withContext(Dispatchers.IO) {
        val fromAddress = request.from ?: user
        val mimeString = MimeBuilder.buildMessage(
            context = context,
            from = fromAddress,
            to = request.to,
            subject = request.subject,
            body = request.body,
            asHtml = request.asHtml,
            cc = request.cc,
            attachments = request.attachments,
            inlineImages = request.inlineImages,
            inReplyTo = request.inReplyTo,
            references = request.references,
            messageId = request.messageId,
        )
        val conn = RawSmtpConnection(server, trustAll = server.trustAllCertificates)
        try {
            openSession(conn, server)
            authenticate(conn, user, auth)
            submitMessage(conn, fromAddress, request.to, request.cc, request.bcc, mimeString)
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
