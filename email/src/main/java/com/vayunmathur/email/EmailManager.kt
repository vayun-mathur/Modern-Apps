package com.vayunmathur.email

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.*
import javax.mail.internet.*
import javax.mail.search.*
import java.util.*

class EmailManager {

    sealed class AuthType {
        data class Password(val value: String) : AuthType()
        data class OAuth2(val accessToken: String) : AuthType()
    }

    private fun getImapSession(auth: AuthType, host: String): Session {
        val properties = Properties()
        properties["mail.store.protocol"] = "imaps"
        properties["mail.imaps.host"] = host
        properties["mail.imaps.port"] = "993"
        properties["mail.imaps.ssl.enable"] = "true"
        properties["mail.imaps.fetchsize"] = "1048576"
        properties["mail.imaps.partialfetch"] = "true"

        if (auth is AuthType.OAuth2) {
            properties["mail.imaps.auth.mechanisms"] = "XOAUTH2"
        }
        return Session.getInstance(properties).also { registerProviders(it) }
    }

    private fun getSmtpSession(auth: AuthType, host: String): Session {
        val properties = Properties()
        properties["mail.transport.protocol"] = "smtps"
        properties["mail.smtps.host"] = host
        properties["mail.smtps.port"] = "465"
        properties["mail.smtps.ssl.enable"] = "true"
        properties["mail.smtps.auth"] = "true"

        if (auth is AuthType.OAuth2) {
            properties["mail.smtps.auth.mechanisms"] = "XOAUTH2"
        }
        return Session.getInstance(properties).also { registerProviders(it) }
    }

    /**
     * Explicitly register JavaMail's bundled providers.
     *
     * On Android (and especially in shrunk APKs with multidex), JavaMail's
     * default discovery mechanism — which reads `META-INF/javamail.providers`
     * and uses ServiceLoader — does not reliably find providers, leading to
     * `NoSuchProviderException("smtps")` / `NoSuchProviderException("imaps")`.
     * Calling `session.setProvider(...)` ahead of `getTransport` / `getStore`
     * avoids the discovery step entirely.
     */
    private fun registerProviders(session: Session) {
        try {
            session.setProvider(Provider(Provider.Type.STORE, "imap", "com.sun.mail.imap.IMAPStore", "Oracle", ""))
            session.setProvider(Provider(Provider.Type.STORE, "imaps", "com.sun.mail.imap.IMAPSSLStore", "Oracle", ""))
            session.setProvider(Provider(Provider.Type.TRANSPORT, "smtp", "com.sun.mail.smtp.SMTPTransport", "Oracle", ""))
            session.setProvider(Provider(Provider.Type.TRANSPORT, "smtps", "com.sun.mail.smtp.SMTPSSLTransport", "Oracle", ""))
            android.util.Log.d("EmailManager", "Providers registered: ${session.providers.joinToString { it.protocol }}")
        } catch (t: Throwable) {
            android.util.Log.e("EmailManager", "registerProviders failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    suspend fun <T> withStore(host: String, user: String, auth: AuthType, block: suspend (Store) -> T): T = withContext(Dispatchers.IO) {
        val session = getImapSession(auth, host)
        val store = session.getStore("imaps")
        try {
            when (auth) {
                is AuthType.Password -> store.connect(host, user, auth.value)
                is AuthType.OAuth2 -> store.connect(host, user, auth.accessToken)
            }
            block(store)
        } finally {
            store.close()
        }
    }

    /** List folders using an already-connected store (no new TCP/TLS handshake). */
    fun fetchFoldersInStore(store: Store, user: String): List<EmailFolder> {
        val folders = store.defaultFolder.list("*")
        return folders.map { folder ->
            EmailFolder(
                accountEmail = user,
                fullName = folder.fullName,
                name = folder.name,
                parentFullName = folder.parent?.fullName?.takeIf { it.isNotEmpty() },
                holdsMessages = (folder.type and Folder.HOLDS_MESSAGES) != 0,
                delimiter = folder.separator.toString(),
            )
        }
    }

    /**
     * Fetch messages from `folderName` using an already-connected [store]. Mirrors
     * [fetchMessages] but doesn't open/close a new connection — caller is expected to
     * have called [withStore] and to be looping over folders inside that lambda.
     */
    suspend fun fetchMessagesInStore(
        store: Store,
        user: String,
        folderName: String,
        limit: Int,
        offset: Int = 0,
        fetchBodies: Boolean = false,
        skipUids: Set<Long> = emptySet(),
    ): Pair<List<EmailMessage>, List<Attachment>> = withContext(Dispatchers.IO) {
        val folder = store.getFolder(folderName)
        if ((folder.type and Folder.HOLDS_MESSAGES) == 0) return@withContext emptyList<EmailMessage>() to emptyList()
        folder.open(Folder.READ_ONLY)
        try {
            fetchMessagesFromOpenFolder(folder, user, folderName, limit, offset, fetchBodies, skipUids)
        } finally {
            try { folder.close(false) } catch (_: Throwable) {}
        }
    }

    /**
     * Fetches new messages from an already-open [folder] without re-opening it.
     * Used by [ImapIdleService] so that we can respond to `EXISTS` push events
     * inline on the same connection instead of going through WorkManager.
     */
    fun fetchMessagesFromOpenFolder(
        folder: Folder,
        user: String,
        folderName: String,
        limit: Int,
        offset: Int,
        fetchBodies: Boolean,
        skipUids: Set<Long>,
    ): Pair<List<EmailMessage>, List<Attachment>> {
        val totalMessages = folder.messageCount
        if (totalMessages == 0) return emptyList<EmailMessage>() to emptyList()
        val end = (totalMessages - offset).coerceAtLeast(1)
        val start = (end - limit + 1).coerceAtLeast(1)
        if (end < 1) return emptyList<EmailMessage>() to emptyList()

        val messages = folder.getMessages(start, end)
        // Cheap UIDs-only fetch first, so we can filter out known UIDs before
        // pulling envelopes (much more bytes).
        folder.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        val uidFolder = folder as? UIDFolder
        val novel = messages.filter { (uidFolder?.getUID(it) ?: -1L) !in skipUids }.toTypedArray()
        if (novel.isEmpty()) return emptyList<EmailMessage>() to emptyList()

        val fp = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(UIDFolder.FetchProfileItem.UID)
            add(FetchProfile.Item.FLAGS)
            add("X-GM-THRID")
            if (fetchBodies) add(FetchProfile.Item.CONTENT_INFO)
        }
        folder.fetch(novel, fp)

        val emailMessages = mutableListOf<EmailMessage>()
        val allAttachments = mutableListOf<Attachment>()
        novel.reversedArray().forEach { msg ->
            val uid = uidFolder?.getUID(msg) ?: -1L
            val (body, isHtml, attachments) = if (fetchBodies) {
                processMessageContent(user, folderName, uid, msg)
            } else {
                Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
            }
            allAttachments.addAll(attachments)
            emailMessages.add(buildEmailMessage(msg, user, folderName, uid, body, isHtml, attachments.isNotEmpty()))
        }
        return emailMessages to allAttachments
    }

    private fun buildEmailMessage(
        msg: javax.mail.Message,
        user: String,
        folderName: String,
        uid: Long,
        body: String?,
        isHtml: Boolean,
        hasAttachments: Boolean,
    ): EmailMessage {
        val gmailThreadId = (msg as? javax.mail.internet.MimeMessage)?.getHeader("X-GM-THRID")?.firstOrNull()
        val serverId = msg.getHeader("Message-ID")?.firstOrNull()
        val refs = msg.getHeader("References")?.firstOrNull()
        val isRead = !msg.isSet(javax.mail.Flags.Flag.SEEN).not() && msg.isSet(javax.mail.Flags.Flag.SEEN)
        val whenMillis = msg.sentDate?.time ?: msg.receivedDate?.time ?: 0L
        return EmailMessage(
            accountEmail = user,
            folderName = folderName,
            id = uid,
            serverId = serverId,
            threadId = gmailThreadId ?: uid.toString(),
            subject = msg.subject ?: "(no subject)",
            from = msg.from?.firstOrNull()?.toString() ?: "",
            to = msg.getRecipients(javax.mail.Message.RecipientType.TO)?.joinToString { it.toString() },
            cc = msg.getRecipients(javax.mail.Message.RecipientType.CC)?.joinToString { it.toString() },
            date = msg.sentDate?.toString() ?: msg.receivedDate?.toString() ?: "",
            dateMillis = whenMillis,
            body = body,
            isHtml = isHtml,
            isRead = isRead,
            references = refs,
            hasAttachments = hasAttachments || hasAttachmentsInfo(msg),
        )
    }

    /**
     * On-demand body + attachments fetch for a single message. Used by the
     * MessageThread screen the first time a message without a stored body is opened.
     */
    suspend fun fetchMessageBody(
        host: String,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): Triple<String?, Boolean, List<Attachment>> = withStore(host, user, auth) { store ->
        fetchMessageBodyInStore(store, user, folderName, uid)
    }

    /**
     * Body+attachments fetch using an already-open [store]. Used by the sync
     * worker's background backfill so we don't open a new TCP/TLS connection
     * for each missing message.
     */
    suspend fun fetchMessageBodyInStore(
        store: Store,
        user: String,
        folderName: String,
        uid: Long,
    ): Triple<String?, Boolean, List<Attachment>> = withContext(Dispatchers.IO) {
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val message = (folder as UIDFolder).getMessageByUID(uid) ?: return@withContext Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
            processMessageContent(user, folderName, uid, message)
        } finally {
            try { folder.close(false) } catch (_: Throwable) {}
        }
    }

    suspend fun fetchFolders(host: String, user: String, auth: AuthType): List<EmailFolder> = withStore(host, user, auth) { store ->
        val folders = store.defaultFolder.list("*")
        folders.map { folder ->
            EmailFolder(
                accountEmail = user,
                fullName = folder.fullName,
                name = folder.name,
                parentFullName = folder.parent?.fullName?.takeIf { it.isNotEmpty() },
                holdsMessages = (folder.type and Folder.HOLDS_MESSAGES) != 0,
                delimiter = folder.separator.toString()
            )
        }
    }

    suspend fun fetchMessages(
        host: String,
        user: String,
        auth: AuthType,
        folderName: String,
        limit: Int,
        offset: Int,
        fetchBodies: Boolean = false,
        /**
         * Set of IMAP UIDs we already have stored locally. Messages whose UID is in
         * this set are skipped entirely — no envelope, no body, no attachments are
         * returned for them. Saves a lot of network on repeated syncs.
         */
        skipUids: Set<Long> = emptySet(),
    ): Pair<List<EmailMessage>, List<Attachment>> = withStore(host, user, auth) { store ->
        val folder = store.getFolder(folderName)
        if ((folder.type and Folder.HOLDS_MESSAGES) == 0) return@withStore emptyList<EmailMessage>() to emptyList()

        folder.open(Folder.READ_ONLY)
        try {
            val totalMessages = folder.messageCount
            if (totalMessages == 0) return@withStore emptyList<EmailMessage>() to emptyList()

            val end = (totalMessages - offset).coerceAtLeast(1)
            val start = (end - limit + 1).coerceAtLeast(1)
            if (end < 1) return@withStore emptyList<EmailMessage>() to emptyList()

            val messages = folder.getMessages(start, end)
            // First fetch just UIDs so we can decide what to skip before doing the
            // (much heavier) envelope + body fetches.
            val uidOnly = FetchProfile().apply {
                add(UIDFolder.FetchProfileItem.UID)
            }
            folder.fetch(messages, uidOnly)

            val uidFolder = folder as? UIDFolder
            val novel = messages.filter { msg ->
                val uid = uidFolder?.getUID(msg) ?: -1L
                uid !in skipUids
            }.toTypedArray()

            if (novel.isEmpty()) return@withStore emptyList<EmailMessage>() to emptyList()

            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(UIDFolder.FetchProfileItem.UID)
                add(FetchProfile.Item.CONTENT_INFO)
                add("X-GM-THRID")
            }
            folder.fetch(novel, fp)

            val emailMessages = mutableListOf<EmailMessage>()
            val allAttachments = mutableListOf<Attachment>()

            novel.reversedArray().forEach { msg ->
                val uid = uidFolder?.getUID(msg) ?: -1L
                val (body, isHtml, attachments) = if (fetchBodies) {
                    processMessageContent(user, folderName, uid, msg)
                } else {
                    Triple(null, false, emptyList<Attachment>())
                }
                
                allAttachments.addAll(attachments)

                emailMessages.add(EmailMessage(
                    accountEmail = user,
                    id = uid,
                    folderName = folderName,
                    serverId = (msg as? MimeMessage)?.messageID,
                    threadId = msg.getHeader("X-GM-THRID")?.firstOrNull() ?: uid.toString(),
                    subject = msg.subject ?: "(No Subject)",
                    from = msg.from?.firstOrNull()?.toString() ?: "Unknown",
                    to = msg.getRecipients(Message.RecipientType.TO)?.joinToString { it.toString() },
                    cc = msg.getRecipients(Message.RecipientType.CC)?.joinToString { it.toString() },
                    date = msg.sentDate?.toString() ?: "",
                    body = body,
                    isHtml = isHtml,
                    isRead = msg.isSet(Flags.Flag.SEEN),
                    references = msg.getHeader("References")?.joinToString(" "),
                    hasAttachments = attachments.isNotEmpty() || hasAttachmentsInfo(msg)
                ))
            }
            emailMessages to allAttachments
        } finally {
            folder.close(false)
        }
    }

    private fun hasAttachmentsInfo(message: Message): Boolean {
        val content = message.content
        return if (content is MimeMultipart) {
            for (i in 0 until content.count) {
                val part = content.getBodyPart(i)
                if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || !part.fileName.isNullOrBlank()) {
                    return true
                }
            }
            false
        } else false
    }

    suspend fun sendMessage(
        context: Context,
        host: String,
        user: String,
        auth: AuthType,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        attachments: List<Uri> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null
    ) = withContext(Dispatchers.IO) {
        val session = getSmtpSession(auth, host)
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(user))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        if (!cc.isNullOrBlank()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
        }
        message.subject = subject
        
        if (inReplyTo != null) {
            message.setHeader("In-Reply-To", inReplyTo)
        }
        if (references != null) {
            message.setHeader("References", references)
        }

        val multipart = MimeMultipart()
        
        // Text part
        val textPart = MimeBodyPart()
        textPart.setText(body)
        multipart.addBodyPart(textPart)

        // Attachments
        for (uri in attachments) {
            val attachmentPart = MimeBodyPart()
            val filename = getFileName(context, uri) ?: "attachment"
            val dataSource = object : javax.activation.DataSource {
                override fun getInputStream() = context.contentResolver.openInputStream(uri) ?: throw Exception("Cannot open URI")
                override fun getOutputStream() = throw Exception("Not supported")
                override fun getContentType() = context.contentResolver.getType(uri) ?: "application/octet-stream"
                override fun getName() = filename
            }
            attachmentPart.dataHandler = DataHandler(dataSource)
            attachmentPart.fileName = filename
            multipart.addBodyPart(attachmentPart)
        }

        message.setContent(multipart)

        val transport = session.getTransport("smtps")
        try {
            when (auth) {
                is AuthType.Password -> transport.connect(host, user, auth.value)
                is AuthType.OAuth2 -> transport.connect(host, user, auth.accessToken)
            }
            transport.sendMessage(message, message.allRecipients)
        } finally {
            transport.close()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) return it.getString(index)
                }
            }
        }
        return uri.path?.let { File(it).name }
    }

    suspend fun downloadAttachment(
        context: Context,
        host: String,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        partId: String,
        fileName: String
    ): String = withStore(host, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid)
            val part = findPartById(msg, partId) ?: throw Exception("Part not found")
            
            val dir = File(context.filesDir, "attachments/$user/$uid")
            dir.mkdirs()
            val file = File(dir, fileName)
            
            part.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } finally {
            folder.close(false)
        }
    }

    private fun findPartById(part: Part, partId: String): Part? {
        if (partId == "0") return part
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as MimeMultipart
            val ids = partId.split(".")
            var currentPart: Part = part
            for (id in ids) {
                val index = id.toIntOrNull() ?: return null
                if (currentPart.content is MimeMultipart) {
                    val innerMp = currentPart.content as MimeMultipart
                    if (index < innerMp.count) {
                        currentPart = innerMp.getBodyPart(index)
                    } else return null
                } else return null
            }
            return currentPart
        }
        return null
    }

    private fun processMessageContent(user: String, folderName: String, uid: Long, part: Part, partId: String = ""): Triple<String?, Boolean, List<Attachment>> {
        if (part.isMimeType("text/plain")) {
            return Triple(part.content.toString(), false, emptyList())
        } else if (part.isMimeType("text/html")) {
            return Triple(part.content.toString(), true, emptyList())
        } else if (part.isMimeType("multipart/*")) {
            val mp = part.content as MimeMultipart
            var finalBody: String? = null
            var finalIsHtml = false
            val attachments = mutableListOf<Attachment>()

            for (i in 0 until mp.count) {
                val bodyPart = mp.getBodyPart(i)
                val currentPartId = if (partId.isEmpty()) i.toString() else "$partId.$i"
                
                if (Part.ATTACHMENT.equals(bodyPart.disposition, ignoreCase = true) || !bodyPart.fileName.isNullOrBlank()) {
                    attachments.add(Attachment(
                        accountEmail = user,
                        folderName = folderName,
                        messageId = uid,
                        partId = currentPartId,
                        fileName = bodyPart.fileName ?: "unnamed",
                        mimeType = bodyPart.contentType.split(";").first(),
                        size = bodyPart.size.toLong()
                    ))
                } else {
                    val (b, h, a) = processMessageContent(user, folderName, uid, bodyPart, currentPartId)
                    attachments.addAll(a)
                    if (finalBody == null || (h && !finalIsHtml)) {
                        finalBody = b
                        finalIsHtml = h
                    }
                }
            }
            return Triple(finalBody, finalIsHtml, attachments)
        }
        return Triple(null, false, emptyList())
    }

    private fun getTextFromMessage(message: Message): Pair<String, Boolean> {
        // Fallback for simple calls, but preferably use processMessageContent
        return try {
            if (message.isMimeType("text/plain")) {
                message.content.toString() to false
            } else if (message.isMimeType("text/html")) {
                message.content.toString() to true
            } else if (message.isMimeType("multipart/*")) {
                getTextFromMimeMultipart(message.content as MimeMultipart)
            } else {
                "" to false
            }
        } catch (e: Exception) {
            "Error loading content: ${e.message}" to false
        }
    }

    private fun getTextFromMimeMultipart(mimeMultipart: MimeMultipart): Pair<String, Boolean> {
        var plainText = ""
        var htmlText = ""
        val count = mimeMultipart.count
        for (i in 0 until count) {
            val bodyPart = mimeMultipart.getBodyPart(i)
            if (bodyPart.isMimeType("text/plain")) {
                plainText += bodyPart.content
            } else if (bodyPart.isMimeType("text/html")) {
                htmlText += bodyPart.content
            } else if (bodyPart.content is MimeMultipart) {
                val (nestedText, nestedIsHtml) = getTextFromMimeMultipart(bodyPart.content as MimeMultipart)
                if (nestedIsHtml) htmlText += nestedText else plainText += nestedText
            }
        }
        return if (htmlText.isNotEmpty()) htmlText to true else plainText to false
    }
}
