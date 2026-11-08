package com.vayunmathur.email.network.smtp

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.ui.composer.InlineAttachment
import java.io.File
import java.security.SecureRandom

/**
 * Minimal MIME builder producing a raw RFC5322 message string with CRLF.
 * Replaces Jakarta MimeMessage + MimeMultipart building.
 *
 * Produces same structure as old EmailManager.sendMessage:
 * - If no inline images: multipart/mixed containing text + attachments
 * - If inline images: multipart/mixed containing multipart/related (text + inline image parts) + attachments
 *
 * Attachments read via ContentResolver.openInputStream or File fallback.
 * Base64 encoded chunked at 76 chars per RFC 2045.
 */

object MimeBuilder {

    private const val TAG = "MimeBuilder"
    private const val MESSAGE_ID_RANDOM_BOUND = 1_000_000
    private const val BOUNDARY_ENTROPY_CHARS = 24
    private const val BOUNDARY_TIME_SUFFIX_LEN = 6
    private const val BASE64_LINE_LEN = 76
    private const val QP_MAX_LINE_LEN = 73
    private const val BYTE_MASK = 0xFF
    private const val ASCII_LF = 10
    private const val ASCII_CR = 13
    private const val ASCII_PRINTABLE_MIN = 32
    private const val ASCII_PRINTABLE_MAX = 126
    private const val QP_SAFE_MIN = 33
    private const val QP_EQUALS = 61
    private const val RFC_2822_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss Z"
    private const val BOUNDARY_RANDOM_BYTES = 18
    private const val BOUNDARY_PREFIX = "----=_Part_"
    private const val BOUNDARY_SEPARATOR = "_"

    private fun buildMessageId(from: String): String {
        val random = SecureRandom().nextInt(MESSAGE_ID_RANDOM_BOUND)
        val domain = from.substringAfter('@').ifBlank { "email.local" }
        return "${System.currentTimeMillis()}.$random@$domain"
    }

    data class BuildResult(val rawMessage: String)

    fun buildMessage(
        context: Context,
        from: String,
        to: String,
        subject: String,
        body: String,
        asHtml: Boolean,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inlineImages: List<InlineAttachment> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
    ): String {
        val sb = StringBuilder()
        appendHeaders(sb, from, to, subject, cc, bcc, inReplyTo, references)

        if (inlineImages.isEmpty() && attachments.isEmpty()) {
            appendSingleTextPart(sb, body, asHtml)
            return sb.toString()
        }

        val mixedBoundary = randomBoundary()
        sb.append("Content-Type: multipart/mixed; boundary=\"$mixedBoundary\"\r\n")
        sb.append("\r\n")
        sb.append("This is a multi-part message in MIME format.\r\n")

        if (inlineImages.isEmpty()) {
            appendMixedTextAndAttachments(sb, context, mixedBoundary, body, asHtml, attachments)
        } else {
            appendMixedRelated(sb, context, mixedBoundary, body, asHtml, attachments, inlineImages)
        }

        return sb.toString()
    }

    private fun appendHeaders(
        sb: StringBuilder,
        from: String,
        to: String,
        subject: String,
        cc: String?,
        bcc: String?,
        inReplyTo: String?,
        references: String?,
    ) {
        // Standard headers
        sb.append("From: $from\r\n")
        val toList = splitAddresses(to)
        sb.append("To: ${toList.joinToString(", ")}\r\n")
        cc?.takeIf { it.isNotBlank() }?.let {
            sb.append("Cc: ${splitAddresses(it).joinToString(", ")}\r\n")
        }
        // Deliberately no Bcc header: blind-copy recipients travel only in
        // the SMTP envelope (SmtpClient.collectRecipients). Writing them
        // into the DATA headers would disclose every blind recipient to
        // all other recipients. The bcc parameter is kept so callers can
        // pass the full recipient set through one entry point.
        sb.append("Subject: ${encodeHeaderIfNeeded(subject)}\r\n")
        val dateFormat = java.text.SimpleDateFormat(RFC_2822_DATE_PATTERN, java.util.Locale.US)
        sb.append("Date: ${dateFormat.format(java.util.Date())}\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        if (!inReplyTo.isNullOrBlank()) sb.append("In-Reply-To: $inReplyTo\r\n")
        if (!references.isNullOrBlank()) sb.append("References: $references\r\n")
        // Message-ID
        val messageId = buildMessageId(from)
        sb.append("Message-ID: <$messageId>\r\n")
    }

    private fun appendSingleTextPart(sb: StringBuilder, body: String, asHtml: Boolean) {
        // Single part text
        if (asHtml) {
            sb.append("Content-Type: text/html; charset=utf-8\r\n")
        } else {
            sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        }
        sb.append("Content-Transfer-Encoding: quoted-printable\r\n")
        sb.append("\r\n")
        sb.append(encodeQuotedPrintableForText(body))
        sb.append("\r\n")
    }

    private fun appendMixedTextAndAttachments(
        sb: StringBuilder,
        context: Context,
        mixedBoundary: String,
        body: String,
        asHtml: Boolean,
        attachments: List<Uri>,
    ) {
        // Text part inside mixed
        sb.append("--$mixedBoundary\r\n")
        appendTextPart(sb, body, asHtml)
        // Attachments
        appendAttachmentParts(sb, context, mixedBoundary, attachments)
        sb.append("--$mixedBoundary--\r\n")
    }

    private fun appendMixedRelated(
        sb: StringBuilder,
        context: Context,
        mixedBoundary: String,
        body: String,
        asHtml: Boolean,
        attachments: List<Uri>,
        inlineImages: List<InlineAttachment>,
    ) {
        val relatedBoundary = randomBoundary()
        // Related part wrapper inside mixed
        sb.append("--$mixedBoundary\r\n")
        sb.append("Content-Type: multipart/related; boundary=\"$relatedBoundary\"\r\n")
        sb.append("\r\n")

        sb.append("--$relatedBoundary\r\n")
        appendTextPart(sb, body, asHtml)

        for (inline in inlineImages) {
            sb.append("--$relatedBoundary\r\n")
            appendInlinePart(sb, context, inline)
        }
        sb.append("--$relatedBoundary--\r\n")

        // Regular attachments after related wrapper
        appendAttachmentParts(sb, context, mixedBoundary, attachments)
        sb.append("--$mixedBoundary--\r\n")
    }

    private fun appendAttachmentParts(
        sb: StringBuilder,
        context: Context,
        mixedBoundary: String,
        attachments: List<Uri>,
    ) {
        for (uri in attachments) {
            sb.append("--$mixedBoundary\r\n")
            appendAttachmentPart(sb, context, uri)
        }
    }

    private fun appendTextPart(sb: StringBuilder, body: String, asHtml: Boolean) {
        if (asHtml) {
            sb.append("Content-Type: text/html; charset=utf-8\r\n")
        } else {
            sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        }
        sb.append("Content-Transfer-Encoding: quoted-printable\r\n")
        sb.append("\r\n")
        sb.append(encodeQuotedPrintableForText(body))
        sb.append("\r\n")
    }

    private fun appendAttachmentPart(sb: StringBuilder, context: Context, uri: Uri) {
        val filename = queryFilename(context, uri) ?: uri.lastPathSegment ?: "attachment"
        val safeName = filename.replace("\"", "_").replace("\r", "").replace("\n", "")
        val mime = context.contentResolver.getType(uri) ?: guessMimeFromName(filename)
        Log.dev(TAG, "Attachment $filename mime=$mime uri=$uri")
        sb.append("Content-Type: $mime; name=\"$safeName\"\r\n")
        sb.append("Content-Disposition: attachment; filename=\"$safeName\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("\r\n")
        val bytes = readBytes(context, uri)
        sb.append(encodeBase64Chunked(bytes))
        sb.append("\r\n")
    }

    private fun appendInlinePart(sb: StringBuilder, context: Context, inline: InlineAttachment) {
        val mime = inline.mimeType.ifBlank { "image/jpeg" }
        val safeName = inline.fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        sb.append("Content-Type: $mime; name=\"$safeName\"\r\n")
        sb.append("Content-Disposition: inline; filename=\"$safeName\"\r\n")
        sb.append("Content-ID: <${inline.cid}>\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("\r\n")
        val bytes = readBytes(context, inline.uri)
        sb.append(encodeBase64Chunked(bytes))
        sb.append("\r\n")
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: File(uri.path ?: "").readBytes()
        } catch (ignored: Exception) {
            Log.status(TAG, "readBytes failed for $uri: ${ignored.message}")
            try { File(uri.path ?: "").readBytes() } catch (_: Exception) { ByteArray(0) }
        }
    }

    private fun queryFilename(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            return queryContentFilename(context, uri)
        }
        return uri.path?.let { File(it).name }
    }

    private fun queryContentFilename(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                readDisplayName(c)
            }
        } catch (_: Exception) { null }
    }

    private fun readDisplayName(c: android.database.Cursor): String? {
        if (!c.moveToFirst()) return null
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        return if (idx != -1) c.getString(idx) else c.getString(0)
    }

    private fun guessMimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".html") -> "text/html"
            else -> "application/octet-stream"
        }
    }

    private fun randomBoundary(): String {
        val bytes = ByteArray(BOUNDARY_RANDOM_BYTES)
        SecureRandom().nextBytes(bytes)
        val entropy = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
            .take(BOUNDARY_ENTROPY_CHARS)
            .replace("-", "")
            .replace("_", "")
        val suffix = System.currentTimeMillis().toString().takeLast(BOUNDARY_TIME_SUFFIX_LEN)
        return BOUNDARY_PREFIX + entropy + BOUNDARY_SEPARATOR + suffix
    }

    fun encodeBase64Chunked(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val sb = StringBuilder()
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + BASE64_LINE_LEN, b64.length)
            sb.append(b64, i, end).append("\r\n")
            i = end
        }
        return sb.toString()
    }

    fun encodeQuotedPrintableForText(text: String): String {
        // Simple QP encoder for text (utf-8 bytes)
        // Encode bytes that need encoding, wrap at 76.
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var lineLen = 0
        for (b in bytes) {
            val step = encodeQpByte(b.toInt() and BYTE_MASK)
            if (step.emitNewline) {
                sb.append("\r\n")
                lineLen = 0
            } else if (step.skip) {
                // \r skip, \n will emit
            } else {
                if (lineLen + step.encoded.length > QP_MAX_LINE_LEN) {
                    sb.append("=\r\n")
                    lineLen = 0
                }
                sb.append(step.encoded)
                lineLen += step.encoded.length
            }
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private data class QpStep(val encoded: String, val emitNewline: Boolean = false, val skip: Boolean = false)

    private fun encodeQpByte(ub: Int): QpStep {
        // Safe chars: 33-60,62-126 except = (61); tab/space special at line end.
        // Encode everything outside 33-126.
        val needsEncode = ub < QP_SAFE_MIN || ub > ASCII_PRINTABLE_MAX || ub == QP_EQUALS
        // Space and tab need encoding at end of line, but we simplify:
        // leave space as is, encode at line wrap.
        if (!needsEncode) {
            // printable ascii
            return QpStep(ub.toChar().toString())
        }
        if (ub == ASCII_LF || ub == ASCII_CR) {
            // Preserve newline: body line breaks remain. We split on \n earlier,
            // so keep \r\n as a literal line break outside the encoding.
            // The loop is over utf-8 bytes including newlines from the body
            // string which may be \n. Handle line breaks manually: on \n,
            // emit \r\n and reset.
            // To keep structure simple, detect \n here and emit a newline.
            if (ub == ASCII_LF) {
                // \n in QP body is a \r\n line break (preserve).
                return QpStep("", emitNewline = true)
            }
            return QpStep("", skip = true)
        }
        return QpStep("=" + "%02X".format(ub))
    }

    private fun splitAddresses(input: String): List<String> {
        return input.split(',', ';').map { it.trim() }.filter { it.isNotBlank() && it.contains('@') }
    }

    private fun encodeHeaderIfNeeded(value: String): String {
        // If ascii-only, return as-is; else encode as RFC2047
        if (value.all { it.code in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX }) return value
        // Encode subject via =?UTF-8?B?...
        val b64 = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Split into multiple encoded-words if too long (max 75 - "=?UTF-8?B??=" ~ 59 char b64 payload ~ 44 bytes)
        // Simplify: single word for now (subjects are usually < 100 chars)
        return "=?UTF-8?B?$b64?="
    }
}
