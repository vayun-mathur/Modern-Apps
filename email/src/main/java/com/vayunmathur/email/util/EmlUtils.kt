package com.vayunmathur.email.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

data class EmlAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class ParsedEml(
    val message: com.vayunmathur.email.EmailMessage,
    val emlAttachments: List<EmlAttachment>,
    val inlineCidMap: Map<String, File> = emptyMap(),
)

object EmlUtils {

    fun parseEml(context: Context, uri: Uri): ParsedEml {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open InputStream for $uri")
        inputStream.use { input ->
            val session = Session.getInstance(Properties())
            val mime = MimeMessage(session, input)

            val syntheticId = uri.toString().hashCode().toLong().toString()

            // Extract CID inline images to cache
            val cidMap = mutableMapOf<String, File>()
            val cidDir = File(context.cacheDir, "eml_cid/$syntheticId").also { it.mkdirs() }

            val (body, isHtml, emlAtts) = parsePart(mime, cidMap, cidDir)

            val from = mime.from?.firstOrNull()?.toString() ?: ""
            val subject = mime.subject ?: "(no subject)"
            val sentDate = mime.sentDate
            val receivedDate = mime.receivedDate
            val dateStr = (sentDate ?: receivedDate)?.toString() ?: ""
            val dateMillis = sentDate?.time ?: receivedDate?.time ?: 0L
            val to = mime.getRecipients(javax.mail.Message.RecipientType.TO)?.joinToString { it.toString() }
            val cc = mime.getRecipients(javax.mail.Message.RecipientType.CC)?.joinToString { it.toString() }
            val serverId = mime.getHeader("Message-ID")?.firstOrNull()
            val refs = mime.getHeader("References")?.firstOrNull() ?: mime.getHeader("In-Reply-To")?.firstOrNull()
            val listUnsub = mime.getHeader("List-Unsubscribe")?.firstOrNull()
            val listUnsubPost = mime.getHeader("List-Unsubscribe-Post")?.firstOrNull()

            val longId = uri.toString().hashCode().toLong()

            val emailMessage = com.vayunmathur.email.EmailMessage(
                accountEmail = "eml-viewer",
                folderName = "EML",
                id = longId,
                serverId = serverId,
                threadId = longId.toString(),
                subject = subject,
                from = from,
                to = to,
                cc = cc,
                date = dateStr,
                dateMillis = dateMillis,
                body = body,
                isHtml = isHtml,
                isRead = true,
                references = refs,
                hasAttachments = emlAtts.isNotEmpty(),
                listUnsubscribe = listUnsub,
                listUnsubscribePost = listUnsubPost,
            )

            return ParsedEml(emailMessage, emlAtts, cidMap)
        }
    }

    private fun parsePart(part: Part, cidMap: MutableMap<String, File>, cidDir: File): Triple<String?, Boolean, List<EmlAttachment>> {
        if (part.isMimeType("text/plain") && !isAttachment(part)) {
            return Triple(extractText(part), false, emptyList())
        }
        if (part.isMimeType("text/html") && !isAttachment(part)) {
            return Triple(extractText(part), true, emptyList())
        }

        if (part.isMimeType("multipart/*")) {
            val mp = part.content as? MimeMultipart ?: return Triple(null, false, emptyList())
            var finalBody: String? = null
            var finalIsHtml = false
            val allAttachments = mutableListOf<EmlAttachment>()

            for (i in 0 until mp.count) {
                val bodyPart = mp.getBodyPart(i)
                val cidHeader = try { bodyPart.getHeader("Content-ID")?.firstOrNull() } catch (_: Exception) { null }
                if (cidHeader != null && isInlineImage(bodyPart)) {
                    val cid = cidHeader.trim().removePrefix("<").removeSuffix(">").trim()
                    if (cid.isNotEmpty()) {
                        val fileName = bodyPart.fileName ?: "$cid.bin"
                        val safeName = fileName.replace(Regex("[/\\\\]"), "_").take(80).ifBlank { "$cid.bin" }
                        val outFile = File(cidDir, safeName)
                        try {
                            bodyPart.inputStream.use { input ->
                                FileOutputStream(outFile).use { out -> input.copyTo(out) }
                            }
                            cidMap[cid] = outFile
                        } catch (_: Exception) { }
                        // Don't add to regular attachments
                        continue
                    }
                }

                if (isAttachment(bodyPart)) {
                    val fileName = bodyPart.fileName ?: "unnamed"
                    val mimeType = bodyPart.contentType?.substringBefore(";")?.trim()?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
                    val bytes = try { bodyPart.inputStream.use { it.readBytes() } } catch (_: Exception) { ByteArray(0) }
                    if (bytes.isNotEmpty() || fileName.isNotBlank()) {
                        allAttachments.add(EmlAttachment(fileName, mimeType, bytes))
                    }
                } else {
                    val (b, h, a) = parsePart(bodyPart, cidMap, cidDir)
                    allAttachments.addAll(a)
                    if (b != null) {
                        if (finalBody == null || (h && !finalIsHtml)) {
                            finalBody = b
                            finalIsHtml = h
                        }
                    }
                }
            }
            return Triple(finalBody, finalIsHtml, allAttachments)
        }

        if (part.fileName != null && part.fileName.isNotBlank()) {
            val fileName = part.fileName
            val mimeType = part.contentType?.substringBefore(";")?.trim()?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
            val cidHeader = try { part.getHeader("Content-ID")?.firstOrNull() } catch (_: Exception) { null }
            if (cidHeader != null && isInlineImage(part)) {
                val cid = cidHeader.trim().removePrefix("<").removeSuffix(">").trim()
                if (cid.isNotEmpty()) {
                    val safeName = fileName.replace(Regex("[/\\\\]"), "_").take(80)
                    val outFile = File(cidDir, safeName)
                    try {
                        part.inputStream.use { input -> FileOutputStream(outFile).use { out -> input.copyTo(out) } }
                        cidMap[cid] = outFile
                    } catch (_: Exception) { }
                    return Triple(null, false, emptyList())
                }
            }
            val bytes = try { part.inputStream.use { it.readBytes() } } catch (_: Exception) { ByteArray(0) }
            return Triple(null, false, listOf(EmlAttachment(fileName, mimeType, bytes)))
        }

        return Triple(null, false, emptyList())
    }

    private fun isAttachment(part: Part): Boolean {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return true
        val fn = part.fileName
        // If has filename and not inline image, treat as attachment
        if (!fn.isNullOrBlank()) {
            if (isInlineImage(part)) {
                // Content-ID present -> inline
                val cid = try { part.getHeader("Content-ID")?.firstOrNull() } catch (_: Exception) { null }
                if (cid != null) return false
            }
            return true
        }
        return false
    }

    private fun isInlineImage(part: Part): Boolean {
        val dispInline = Part.INLINE.equals(part.disposition, ignoreCase = true)
        val isImage = part.isMimeType("image/*")
        return dispInline || isImage
    }

    private fun extractText(part: Part): String? {
        return try {
            when (val c = part.content) {
                is String -> c
                else -> part.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (_: Exception) {
            try { part.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { null }
        }
    }

    fun sanitizeFileName(input: String, fallback: String = "email"): String {
        var base = input.ifBlank { fallback }.trim()
        if (base.endsWith(".eml", ignoreCase = true)) base = base.dropLast(4)
        base = base.take(60).trim()
        base = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        base = base.replace(Regex("_+"), "_")
        base = base.trim('_', '.', ' ')
        if (base.isBlank()) base = fallback
        return "$base.eml"
    }
}
