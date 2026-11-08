package com.vayunmathur.email.platform

import android.content.Context
import android.net.Uri
import com.vayunmathur.email.ui.composer.InlineAttachment
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.OutlookOAuth
import com.vayunmathur.email.network.imap.ImapClient
import com.vayunmathur.email.network.smtp.SmtpClient
import java.io.File
import java.io.OutputStream

/**
 * Email server config + auth resolution + raw IMAP/SMTP facade.
 * Outlook uses OAuth2 (XOAUTH2), others use app passwords.
 * Raw sockets only — no Jakarta Mail.
 */

data class ServerConfig(val host: String, val port: Int, val useSsl: Boolean) {
    val imapProtocol: String get() = if (useSsl) "imaps" else "imap"
    val smtpProtocol: String get() = if (useSsl) "smtps" else "smtp"
}

fun EmailAccount.imapServer(): ServerConfig = ServerConfig(imapHost, imapPort, imapUseSsl)
fun EmailAccount.smtpServer(): ServerConfig = ServerConfig(smtpHost, smtpPort, smtpUseSsl)
fun EmailAccount.loginUser(): String = username.ifBlank { email }

suspend fun EmailAccount.resolveAuth(context: Context): EmailManager.AuthType {
    if (authType == "oauth2") {
        if (provider == "outlook" || provider == com.vayunmathur.email.data.PROVIDER_OUTLOOK) {
            val token = OutlookOAuth.freshAccessToken(context, this)
                ?: error("Account $email is missing an OAuth access token")
            return EmailManager.AuthType.OAuth(token)
        }
        // Legacy non-Outlook oauth2 account — fall back to encrypted password if present
        val cipher = passwordEncrypted
        val iv = passwordIv
        if (cipher != null && iv != null) {
            return EmailManager.AuthType.Password(CredentialCrypto.decrypt(cipher, iv))
        }
        error("OAuth account $email needs to be re-added with an app password")
    }
    val cipher = passwordEncrypted ?: error("Account $email is missing passwordEncrypted")
    val iv = passwordIv ?: error("Account $email is missing passwordIv")
    return EmailManager.AuthType.Password(CredentialCrypto.decrypt(cipher, iv))
}

class EmailManager {

    sealed class AuthType {
        data class Password(val value: String) : AuthType()
        data class OAuth(val token: String) : AuthType()
    }

    data class FullFetchResult(val contentTriple: Triple<String?, Boolean, List<Attachment>>)

    suspend fun fetchFolders(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        accountEmail: String = user,
    ): List<EmailFolder> =
        ImapClient.fetchFolders(server, user, auth, accountEmail)

    suspend fun fetchMessages(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        limit: Int,
        offset: Int = 0,
        fetchBodies: Boolean = false,
        skipUids: Set<Long> = emptySet(),
        accountEmail: String = user,
    ): Pair<List<EmailMessage>, List<Attachment>> =
        ImapClient.fetchMessages(server, user, auth, folderName, limit, offset, fetchBodies, skipUids, null, accountEmail)

    suspend fun fetchMessageBody(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        accountEmail: String = user,
    ): Triple<String?, Boolean, List<Attachment>> =
        ImapClient.fetchMessageBody(server, user, auth, folderName, uid, null, accountEmail)

    suspend fun fetchFullForBody(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        accountEmail: String = user,
    ): FullFetchResult = ImapClient.fetchFullForBody(context, server, user, auth, folderName, uid, accountEmail)

    suspend fun fetchCidMap(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): Map<String, File> = ImapClient.fetchCidMap(context, server, user, auth, folderName, uid)

    suspend fun setSeenFlag(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        seen: Boolean,
    ) = ImapClient.setSeenFlag(server, user, auth, folderName, uid, seen)

    suspend fun setSeenFlagSet(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uids: List<Long>,
        seen: Boolean,
    ) = ImapClient.setSeenFlagSet(server, user, auth, folderName, uids, seen)

    suspend fun deleteMessage(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ) = ImapClient.deleteMessage(server, user, auth, folderName, uid)

    suspend fun downloadAttachment(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        partId: String,
        fileName: String,
        mimeType: String,
    ): String = ImapClient.downloadAttachment(context, server, user, auth, folderName, uid, partId, fileName, mimeType)

    suspend fun fetchRawMessageBytes(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): ByteArray = ImapClient.fetchRawMessageBytes(server, user, auth, folderName, uid)

    suspend fun fetchRawMessageTo(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        output: OutputStream,
    ) = ImapClient.fetchRawMessageTo(server, user, auth, folderName, uid, output)

    suspend fun sendMessage(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
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
    ) = SmtpClient.sendMessage(
        context,
        server,
        user,
        auth,
        to,
        subject,
        body,
        cc,
        bcc,
        attachments,
        inlineImages,
        inReplyTo,
        references,
        from,
        asHtml,
    )
}
