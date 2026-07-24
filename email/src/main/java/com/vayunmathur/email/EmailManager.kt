package com.vayunmathur.email

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.vayunmathur.email.composer.InlineAttachment
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.OutlookOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.*
import javax.mail.internet.*
import java.util.*

data class ServerConfig(val host: String, val port: Int, val useSsl: Boolean) {
    val imapProtocol: String get() = if (useSsl) "imaps" else "imap"
    val smtpProtocol: String get() = if (useSsl) "smtps" else "smtp"
}

fun EmailAccount.imapServer(): ServerConfig = ServerConfig(imapHost, imapPort, imapUseSsl)
fun EmailAccount.smtpServer(): ServerConfig = ServerConfig(smtpHost, smtpPort, smtpUseSsl)
fun EmailAccount.loginUser(): String = username.ifBlank { email }

suspend fun EmailAccount.resolveAuth(context: Context): EmailManager.AuthType {
    if (authType == "oauth2") {
        val token = OutlookOAuth.freshAccessToken(context, this)
            ?: error("Account $email is missing an OAuth access token")
        return EmailManager.AuthType.OAuth(token)
    }
    val cipher = passwordEncrypted ?: error("Account ${email} is missing passwordEncrypted")
    val iv = passwordIv ?: error("Account ${email} is missing passwordIv")
    return EmailManager.AuthType.Password(CredentialCrypto.decrypt(cipher, iv))
}

fun Folder.closeQuietly(expunge: Boolean = false) {
    try { close(expunge) } catch (_: Throwable) {}
}

class EmailManager {

    sealed class AuthType {
        data class Password(val value: String) : AuthType()
        data class OAuth(val token: String) : AuthType()
    }

    private val AuthType.credential: String
        get() = when (this) {
            is AuthType.Password -> value
            is AuthType.OAuth -> token
        }

    private fun getImapSession(server: ServerConfig, oauth: Boolean = false): Session {
        val proto = server.imapProtocol
        val properties = Properties().apply {
            this["mail.store.protocol"] = proto
            this["mail.$proto.host"] = server.host
            this["mail.$proto.port"] = server.port.toString()
            this["mail.$proto.fetchsize"] = "1048576"
            this["mail.$proto.partialfetch"] = "true"
            if (oauth) {
                this["mail.$proto.auth.mechanisms"] = "XOAUTH2"
                this["mail.$proto.auth.login.disable"] = "true"
                this["mail.$proto.auth.plain.disable"] = "true"
            }
            if (server.useSsl) this["mail.$proto.ssl.enable"] = "true"
            else {
                this["mail.$proto.starttls.enable"] = "true"
                this["mail.$proto.starttls.required"] = "true"
            }
        }
        return Session.getInstance(properties).also { registerProviders(it) }
    }

    private fun getSmtpSession(server: ServerConfig, oauth: Boolean = false): Session {
        val proto = server.smtpProtocol
        val properties = Properties().apply {
            this["mail.transport.protocol"] = proto
            this["mail.$proto.host"] = server.host
            this["mail.$proto.port"] = server.port.toString()
            this["mail.$proto.auth"] = "true"
            if (oauth) {
                this["mail.$proto.auth.mechanisms"] = "XOAUTH2"
                this["mail.$proto.auth.login.disable"] = "true"
                this["mail.$proto.auth.plain.disable"] = "true"
            }
            if (server.useSsl) this["mail.$proto.ssl.enable"] = "true"
            else {
                this["mail.$proto.starttls.enable"] = "true"
                this["mail.$proto.starttls.required"] = "true"
            }
        }
        return Session.getInstance(properties).also { registerProviders(it) }
    }

    private fun registerProviders(session: Session) {
        try {
            session.setProvider(Provider(Provider.Type.STORE, "imap", "com.sun.mail.imap.IMAPStore", "Oracle", ""))
            session.setProvider(Provider(Provider.Type.STORE, "imaps", "com.sun.mail.imap.IMAPSSLStore", "Oracle", ""))
            session.setProvider(Provider(Provider.Type.TRANSPORT, "smtp", "com.sun.mail.smtp.SMTPTransport", "Oracle", ""))
            session.setProvider(Provider(Provider.Type.TRANSPORT, "smtps", "com.sun.mail.smtp.SMTPSSLTransport", "Oracle", ""))
        } catch (t: Throwable) {
            android.util.Log.e("EmailManager", "registerProviders failed: ${t.message}", t)
        }
    }

    suspend fun <T> withStore(server: ServerConfig, user: String, auth: AuthType, block: suspend (Store) -> T): T = withContext(Dispatchers.IO) {
        val oauth = auth is AuthType.OAuth
        val session = getImapSession(server, oauth)
        val store = session.getStore(server.imapProtocol)
        try {
            store.connect(server.host, server.port, user, auth.credential)
            block(store)
        } finally { store.close() }
    }

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
        try { fetchMessagesFromOpenFolder(folder, user, folderName, limit, offset, fetchBodies, skipUids) }
        finally { folder.closeQuietly() }
    }

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
            val (body, isHtml, attachments) = if (fetchBodies) processMessageContent(user, folderName, uid, msg)
            else Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
            allAttachments.addAll(attachments)
            emailMessages.add(buildEmailMessage(msg, user, folderName, uid, body, isHtml, attachments.isNotEmpty(), fetchBodies))
        }
        return emailMessages to allAttachments
    }

    private fun buildEmailMessage(
        msg: Message,
        user: String,
        folderName: String,
        uid: Long,
        body: String?,
        isHtml: Boolean,
        hasAttachments: Boolean,
        fetchBodies: Boolean,
    ): EmailMessage {
        val gmailThreadId = (msg as? MimeMessage)?.getHeader("X-GM-THRID")?.firstOrNull()
        val serverId = msg.getHeader("Message-ID")?.firstOrNull()
        val refs = msg.getHeader("References")?.firstOrNull()
        val listUnsub = msg.getHeader("List-Unsubscribe")?.firstOrNull()
        val listUnsubPost = msg.getHeader("List-Unsubscribe-Post")?.firstOrNull()
        val isRead = msg.isSet(Flags.Flag.SEEN)
        val whenMillis = msg.sentDate?.time ?: msg.receivedDate?.time ?: 0L
        return EmailMessage(
            accountEmail = user,
            folderName = folderName,
            id = uid,
            serverId = serverId,
            threadId = gmailThreadId ?: uid.toString(),
            subject = msg.subject ?: "(no subject)",
            from = msg.from?.firstOrNull()?.toString() ?: "",
            to = msg.getRecipients(Message.RecipientType.TO)?.joinToString { it.toString() },
            cc = msg.getRecipients(Message.RecipientType.CC)?.joinToString { it.toString() },
            date = msg.sentDate?.toString() ?: msg.receivedDate?.toString() ?: "",
            dateMillis = whenMillis,
            body = body,
            isHtml = isHtml,
            isRead = isRead,
            references = refs,
            hasAttachments = hasAttachments || (fetchBodies && hasAttachmentsInfo(msg)),
            listUnsubscribe = listUnsub,
            listUnsubscribePost = listUnsubPost,
        )
    }

    suspend fun fetchMessageBody(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): Triple<String?, Boolean, List<Attachment>> = withStore(server, user, auth) { store ->
        fetchMessageBodyInStore(store, user, folderName, uid)
    }

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
        } finally { folder.closeQuietly() }
    }

    suspend fun fetchFolders(server: ServerConfig, user: String, auth: AuthType): List<EmailFolder> = withStore(server, user, auth) { store ->
        fetchFoldersInStore(store, user)
    }

    suspend fun fetchMessages(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        limit: Int,
        offset: Int,
        fetchBodies: Boolean = false,
        skipUids: Set<Long> = emptySet(),
    ): Pair<List<EmailMessage>, List<Attachment>> = withStore(server, user, auth) { store ->
        fetchMessagesInStore(store, user, folderName, limit, offset, fetchBodies, skipUids)
    }

    private fun hasAttachmentsInfo(message: Message): Boolean {
        val content = message.content as? MimeMultipart ?: return false
        return (0 until content.count).any { i ->
            val part = content.getBodyPart(i)
            Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || !part.fileName.isNullOrBlank()
        }
    }

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
        asHtml: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val oauth = auth is AuthType.OAuth
        val session = getSmtpSession(server, oauth)
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from ?: user))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        if (!cc.isNullOrBlank()) message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
        if (!bcc.isNullOrBlank()) message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc))
        message.subject = subject
        if (inReplyTo != null) message.setHeader("In-Reply-To", inReplyTo)
        if (references != null) message.setHeader("References", references)

        val textPart = MimeBodyPart().apply {
            if (asHtml) setContent(body, "text/html; charset=utf-8") else setText(body)
        }

        val finalMultipart: MimeMultipart
        if (inlineImages.isEmpty()) {
            finalMultipart = MimeMultipart("mixed")
            finalMultipart.addBodyPart(textPart)
            for (uri in attachments) finalMultipart.addBodyPart(buildAttachmentPart(context, uri))
        } else {
            val related = MimeMultipart("related")
            related.addBodyPart(textPart)
            for (inline in inlineImages) {
                val inlinePart = MimeBodyPart().apply {
                    val ds = object : DataSource {
                        override fun getInputStream() = try {
                            context.contentResolver.openInputStream(inline.uri) ?: File(inline.uri.path ?: "").inputStream()
                        } catch (_: Exception) { File(inline.uri.path ?: "").inputStream() }
                        override fun getOutputStream() = throw Exception("Not supported")
                        override fun getContentType() = inline.mimeType.ifBlank { "image/jpeg" }
                        override fun getName() = inline.fileName
                    }
                    dataHandler = DataHandler(ds)
                    setHeader("Content-ID", "<${inline.cid}>")
                    setHeader("Content-Disposition", "inline; filename=\"${inline.fileName}\"")
                    fileName = inline.fileName
                }
                related.addBodyPart(inlinePart)
            }
            val relatedWrapper = MimeBodyPart().apply { setContent(related) }
            finalMultipart = MimeMultipart("mixed")
            finalMultipart.addBodyPart(relatedWrapper)
            for (uri in attachments) finalMultipart.addBodyPart(buildAttachmentPart(context, uri))
        }

        message.setContent(finalMultipart)

        val transport = session.getTransport(server.smtpProtocol)
        try {
            transport.connect(server.host, server.port, user, auth.credential)
            transport.sendMessage(message, message.allRecipients)
        } finally { transport.close() }
    }

    private fun buildAttachmentPart(context: Context, uri: Uri): MimeBodyPart {
        val attachmentPart = MimeBodyPart()
        val filename = getFileName(context, uri) ?: "attachment"
        val dataSource = object : DataSource {
            override fun getInputStream() = try {
                context.contentResolver.openInputStream(uri) ?: File(uri.path ?: "").inputStream()
            } catch (_: Exception) { File(uri.path ?: "").inputStream() }
            override fun getOutputStream() = throw Exception("Not supported")
            override fun getContentType() = context.contentResolver.getType(uri) ?: "application/octet-stream"
            override fun getName() = filename
        }
        attachmentPart.dataHandler = DataHandler(dataSource)
        attachmentPart.fileName = filename
        attachmentPart.setHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        return attachmentPart
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

    suspend fun setSeenFlag(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        seen: Boolean,
    ) = withStore(server, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_WRITE)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid) ?: return@withStore
            msg.setFlag(Flags.Flag.SEEN, seen)
        } finally { folder.closeQuietly() }
    }

    suspend fun deleteMessage(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ) = withStore(server, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_WRITE)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid) ?: return@withStore
            msg.setFlag(Flags.Flag.DELETED, true)
            folder.expunge()
        } finally { folder.closeQuietly(true) }
    }

    suspend fun downloadAttachment(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        partId: String,
        fileName: String,
        mimeType: String
    ): String = withStore(server, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid)
            val part = findPartById(msg, partId) ?: throw Exception("Part not found")
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Could not create Downloads entry")
            try {
                resolver.openOutputStream(itemUri)?.use { output -> part.inputStream.use { input -> input.copyTo(output) } }
                    ?: throw Exception("Could not open output stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }
            itemUri.toString()
        } finally { folder.closeQuietly() }
    }

    private fun findPartById(part: Part, partId: String): Part? {
        if (partId == "0") return part
        if (part.isMimeType("multipart/*")) {
            val ids = partId.split(".")
            var currentPart: Part = part
            for (id in ids) {
                val index = id.toIntOrNull() ?: return null
                if (currentPart.content is MimeMultipart) {
                    val innerMp = currentPart.content as MimeMultipart
                    if (index < innerMp.count) currentPart = innerMp.getBodyPart(index) else return null
                } else return null
            }
            return currentPart
        }
        return null
    }

    suspend fun fetchRawMessageBytes(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): ByteArray = withStore(server, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid) ?: throw IllegalStateException("Message UID $uid not found in $folderName")
            val baos = ByteArrayOutputStream()
            (msg as? MimeMessage)?.writeTo(baos) ?: msg.dataHandler.writeTo(baos)
            baos.toByteArray()
        } finally { folder.closeQuietly() }
    }

    suspend fun fetchRawMessageTo(
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
        output: OutputStream,
    ) = withStore(server, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val msg = (folder as UIDFolder).getMessageByUID(uid) ?: throw IllegalStateException("Message UID $uid not found in $folderName")
            (msg as? MimeMessage)?.writeTo(output) ?: msg.dataHandler.writeTo(output)
        } finally { folder.closeQuietly() }
    }

    internal fun processMessageContent(user: String, folderName: String, uid: Long, part: Part, partId: String = ""): Triple<String?, Boolean, List<Attachment>> {
        when {
            part.isMimeType("text/plain") -> return Triple(part.content.toString(), false, emptyList())
            part.isMimeType("text/html") -> return Triple(part.content.toString(), true, emptyList())
            part.isMimeType("multipart/*") -> {
                val mp = part.content as MimeMultipart
                var finalBody: String? = null
                var finalIsHtml = false
                val attachments = mutableListOf<Attachment>()
                for (i in 0 until mp.count) {
                    val bodyPart = mp.getBodyPart(i)
                    val currentPartId = if (partId.isEmpty()) i.toString() else "$partId.$i"
                    val contentId = try { bodyPart.getHeader("Content-ID")?.firstOrNull() } catch (_: Exception) { null }
                    val isInlineImage = contentId != null && (bodyPart.isMimeType("image/*") || Part.INLINE.equals(bodyPart.disposition, ignoreCase = true))
                    if (!isInlineImage && (Part.ATTACHMENT.equals(bodyPart.disposition, ignoreCase = true) || !bodyPart.fileName.isNullOrBlank())) {
                        attachments.add(Attachment(user, folderName, uid, currentPartId, bodyPart.fileName ?: "unnamed", bodyPart.contentType.split(";").first(), bodyPart.size.toLong()))
                    } else if (!isInlineImage) {
                        val (b, h, a) = processMessageContent(user, folderName, uid, bodyPart, currentPartId)
                        attachments.addAll(a)
                        if (finalBody == null || (h && !finalIsHtml)) { finalBody = b; finalIsHtml = h }
                    }
                }
                return Triple(finalBody, finalIsHtml, attachments)
            }
        }
        return Triple(null, false, emptyList())
    }

    data class FullFetchResult(val contentTriple: Triple<String?, Boolean, List<Attachment>>)

    suspend fun fetchFullForBody(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): FullFetchResult = withStore(server, user, auth) { store ->
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)
        try {
            val message = (folder as UIDFolder).getMessageByUID(uid)
            if (message == null) return@withStore FullFetchResult(Triple(null, false, emptyList()))
            val ctx = context.applicationContext
            try { extractInlineCidMapSync(ctx, message, uid) } catch (_: Exception) { }
            val triple = processMessageContent(user, folderName, uid, message, "")
            FullFetchResult(triple)
        } finally { folder.closeQuietly() }
    }

    suspend fun fetchCidMap(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: AuthType,
        folderName: String,
        uid: Long,
    ): Map<String, File> = withContext(Dispatchers.IO) {
        withStore(server, user, auth) { store ->
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                val msg = (folder as UIDFolder).getMessageByUID(uid) ?: return@withStore emptyMap<String, File>()
                extractInlineCidMapSync(context, msg, uid)
            } finally { folder.closeQuietly() }
        }
    }

    private fun extractInlineCidMapSync(context: Context, part: Part, uid: Long): Map<String, File> {
        val map = mutableMapOf<String, File>()
        fun walk(p: Part) {
            try {
                if (p.isMimeType("multipart/*")) {
                    val mp = p.content as? MimeMultipart ?: return
                    for (i in 0 until mp.count) walk(mp.getBodyPart(i))
                } else {
                    val cidHeader = try { p.getHeader("Content-ID")?.firstOrNull() } catch (_: Exception) { null }
                    if (cidHeader != null) {
                        val cid = cidHeader.trim().removePrefix("<").removeSuffix(">").trim()
                        if (cid.isNotEmpty()) {
                            val dir = File(context.cacheDir, "cid/$uid").also { it.mkdirs() }
                            val safeName = (p.fileName?.takeIf { it.isNotBlank() } ?: "${cid.hashCode()}.bin").replace(Regex("[/\\\\]"), "_")
                            val outFile = File(dir, safeName)
                            if (!outFile.exists()) {
                                try { p.inputStream.use { input -> outFile.outputStream().use { out -> input.copyTo(out) } } } catch (_: Exception) { }
                            }
                            if (outFile.exists()) {
                                map[cid] = outFile
                                try { File(dir, "$cid.meta").writeText(outFile.name) } catch (_: Exception) { }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        walk(part)
        val dir = File(context.cacheDir, "cid/$uid")
        if (dir.exists()) {
            dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { meta ->
                val cid = meta.name.removeSuffix(".meta")
                if (cid !in map) {
                    val targetName = try { meta.readText().trim() } catch (_: Exception) { null }
                    if (targetName != null) {
                        val file = File(dir, targetName)
                        if (file.exists()) map[cid] = file
                    }
                }
            }
        }
        return map
    }
}
