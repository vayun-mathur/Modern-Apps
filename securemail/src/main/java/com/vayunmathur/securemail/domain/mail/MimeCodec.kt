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
     * Minimal parser for the MIME skeleton the composer emits (headers +
     * trailing `--boundary--`). Returns (subject, body, from). The payload is
     * already authenticated PGP plaintext at this point; this only splits it.
     */
    fun parseMime(mime: String): Triple<String, String, String> {
        val lines = mime.lines()
        var from = ""
        var subject = ""
        var i = 0
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            when {
                line.startsWith("From:", ignoreCase = true) ->
                    from = line.substringAfter(':').trim()
                line.startsWith("Subject:", ignoreCase = true) ->
                    subject = line.substringAfter(':').trim()
            }
            i++
        }
        // Skip the blank line plus the multipart preamble (boundary markers and
        // the Version part); the body is the last text block before --boundary--.
        val body = StringBuilder()
        var inBody = false
        for (line in lines.drop(i)) {
            if (line.startsWith("--maemail-")) {
                inBody = false
                continue
            }
            if (line.startsWith("Content-Type: text/plain", ignoreCase = true)) {
                inBody = false // headers of the part follow; blank line starts text
                continue
            }
            if (inBody) body.appendLine(line)
            if (line.isBlank()) inBody = true
        }
        return Triple(subject, body.toString().trimEnd(), from)
    }
}
