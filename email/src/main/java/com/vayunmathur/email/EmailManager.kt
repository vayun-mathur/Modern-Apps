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
        return Session.getInstance(properties)
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
        return Session.getInstance(properties)
    }

    private suspend fun <T> withStore(host: String, user: String, auth: AuthType, block: suspend (Store) -> T): T = withContext(Dispatchers.IO) {
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
        fetchBodies: Boolean = false
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
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(UIDFolder.FetchProfileItem.UID)
                add(FetchProfile.Item.CONTENT_INFO)
                add("X-GM-THRID")
            }
            folder.fetch(messages, fp)

            val uidFolder = folder as? UIDFolder
            val emailMessages = mutableListOf<EmailMessage>()
            val allAttachments = mutableListOf<Attachment>()

            messages.reversedArray().forEach { msg ->
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
