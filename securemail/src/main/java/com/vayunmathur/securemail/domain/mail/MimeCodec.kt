package com.vayunmathur.securemail.domain.mail

/**
 * Pure MIME helpers shared by the receive path (service-side decrypt on
 * arrival) and the UI-side decrypt fallback. No Android imports — base64
 * stays at the call sites.
 */
object MimeCodec {

    /**
     * Extracts the raw PGP bytes from an RFC 3156 multipart/encrypted envelope
     * as base64. The data part is normally an ASCII-armored `PGP MESSAGE`
     * (what Proton/GnuPG/Thunderbird send, and what SecureMail sends since it
     * federates); older SecureMail blobs carried bare base64 instead, which is
     * still accepted. Returns null when the input is not such an envelope.
     */
    fun extractPgpPayloadB64(envelope: ByteArray): String? {
        return try {
            val text = envelope.toString(Charsets.UTF_8)
            if (!text.contains("application/pgp-encrypted")) return null
            val armorStart = text.indexOf(ARMOR_BEGIN)
            if (armorStart >= 0) return dearmorBody(text.substring(armorStart))
            // Legacy bare-base64 part: body follows the octet-stream headers and
            // runs to the next MIME boundary line (any boundary, not just ours).
            val octetIdx = text.indexOf("application/octet-stream")
            if (octetIdx < 0) return null
            val bodyStart = text.indexOf("\r\n\r\n", octetIdx).takeIf { it >= 0 }
                ?: text.indexOf("\n\n", octetIdx).takeIf { it >= 0 }
                ?: return null
            val b64 = text.substring(bodyStart).lineSequence()
                .map { it.trim() }
                .dropWhile { it.isEmpty() }
                .takeWhile { !it.startsWith("--") }
                .joinToString("")
            b64.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Base64 body of an armored block starting at `-----BEGIN PGP MESSAGE-----`
     * (RFC 9580 §6.2): skips armor headers (`Key: value` up to the blank line),
     * stops at the CRC24 line (`=xxxx`) or the END line.
     */
    private fun dearmorBody(armored: String): String? {
        val lines = armored.lineSequence().map { it.trim() }.drop(1).toList()
        val bodyStart = if (lines.firstOrNull()?.contains(": ") == true) {
            lines.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 }?.plus(1) ?: return null
        } else {
            lines.indexOfFirst { it.isNotEmpty() }.takeIf { it >= 0 } ?: return null
        }
        val b64 = lines.drop(bodyStart)
            .takeWhile { !it.startsWith("=") && !it.startsWith("-----") }
            .joinToString("")
        return b64.ifEmpty { null }
    }

    private const val ARMOR_BEGIN = "-----BEGIN PGP MESSAGE-----"

    /**
     * A parsed message. [body] is always plain text (previews, quoting, and the
     * fallback view); [html] is the sender's HTML part when there is one, which
     * the reader renders so links and formatting survive.
     */
    data class MimeContent(val subject: String, val body: String, val from: String, val html: String?)

    /**
     * Parses a MIME message: our own composer's text/plain, and whatever other
     * providers send (Proton, Gmail): folded headers, RFC 2047 encoded-words,
     * quoted-printable/base64 bodies in any charset, and multipart trees.
     */
    fun parseMime(mime: String): MimeContent {
        val (headers, body) = splitEntity(mime)
        val html = findPart(headers, body, "text/html")
        val charset = param(headers["content-type"].orEmpty(), "charset")
        val text = findPart(headers, body, "text/plain")
            ?: html?.let(::htmlToText)
            ?: decodeTransfer(body, headers["content-transfer-encoding"], charset)
        return MimeContent(
            subject = decodeWords(headers["subject"].orEmpty()),
            body = text.trimEnd(),
            from = decodeWords(headers["from"].orEmpty()),
            html = html,
        )
    }

    /** Header map (lowercase names, unfolded values; first occurrence wins) + raw body. */
    private fun splitEntity(entity: String): Pair<Map<String, String>, String> {
        val text = entity.replace("\r\n", "\n")
        val end = text.indexOf("\n\n").takeIf { it >= 0 }
        val headerBlock = if (end == null) text else text.substring(0, end)
        val body = if (end == null) "" else text.substring(end + 2)
        val headers = mutableMapOf<String, String>()
        var name: String? = null
        val value = StringBuilder()
        fun flush() {
            name?.let { headers.putIfAbsent(it, value.toString().trim()) }
        }
        for (line in headerBlock.split("\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) {
                value.append(' ').append(line.trim())
            } else {
                flush()
                val colon = line.indexOf(':')
                name = if (colon > 0) line.substring(0, colon).trim().lowercase() else null
                value.setLength(0)
                if (colon > 0) value.append(line.substring(colon + 1))
            }
        }
        flush()
        return headers to body
    }

    /** Decoded text of the first part of [wanted] type, searching multipart trees depth-first. */
    private fun findPart(headers: Map<String, String>, body: String, wanted: String): String? {
        val type = headers["content-type"].orEmpty()
        val mediaType = type.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        if (mediaType.startsWith("multipart/")) {
            val boundary = param(type, "boundary") ?: return null
            return multipartParts(body, boundary).firstNotNullOfOrNull { part ->
                val (h, b) = splitEntity(part)
                findPart(h, b, wanted)
            }
        }
        if (mediaType != wanted) return null
        return decodeTransfer(body, headers["content-transfer-encoding"], param(type, "charset"))
    }

    private fun multipartParts(body: String, boundary: String): List<String> {
        val parts = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in body.split("\n")) {
            val trimmed = line.trimEnd()
            when (trimmed) {
                "--$boundary--" -> {
                    current?.let { parts += it.toString() }
                    return parts
                }
                "--$boundary" -> {
                    current?.let { parts += it.toString() }
                    current = StringBuilder()
                }
                else -> current?.append(line)?.append('\n')
            }
        }
        current?.let { parts += it.toString() }
        return parts
    }

    private fun param(headerValue: String, key: String): String? =
        headerValue.split(';').drop(1).map { it.trim() }
            .firstOrNull { it.lowercase().startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.trim('"')

    private fun charsetOf(name: String?): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)

    private fun decodeTransfer(body: String, encoding: String?, charset: String?): String =
        when (encoding?.trim()?.lowercase()) {
            "base64" -> runCatching {
                String(java.util.Base64.getMimeDecoder().decode(body.trim()), charsetOf(charset))
            }.getOrDefault(body)
            "quoted-printable" -> String(decodeQuotedPrintable(body, underscoreIsSpace = false), charsetOf(charset))
            else -> body
        }

    private fun decodeQuotedPrintable(text: String, underscoreIsSpace: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val src = text.replace("=\n", "")
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val hex = if (c == '=' && i + 2 < src.length) src.substring(i + 1, i + 3).toIntOrNull(16) else null
            when {
                hex != null -> { out.write(hex); i += 3 }
                c == '_' && underscoreIsSpace -> { out.write(' '.code); i++ }
                else -> { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
        }
        return out.toByteArray()
    }

    /** RFC 2047 encoded-words (`=?charset?B|Q?text?=`), e.g. non-ASCII subjects. */
    private fun decodeWords(value: String): String =
        Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=(\s+(?==\?))?""").replace(value) { m ->
            val (charset, enc, data) = m.destructured
            runCatching {
                val bytes = if (enc.equals("B", ignoreCase = true)) {
                    java.util.Base64.getDecoder().decode(data)
                } else {
                    decodeQuotedPrintable(data, underscoreIsSpace = true)
                }
                String(bytes, charsetOf(charset))
            }.getOrDefault(m.value)
        }

    /** Readable plain text from an HTML body (for previews and the no-HTML fallback). */
    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|tr|li|h[1-6]|blockquote)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&amp;", "&")
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
