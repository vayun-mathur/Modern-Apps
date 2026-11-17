package com.vayunmathur.code.domain
import java.nio.charset.Charset

/** The line ending a file uses on disk; the buffer is always normalized to [LF] while editing. */
enum class LineEnding(val sequence: String) {
    LF("\n"),
    CRLF("\r\n"),
}

/**
 * A file's text decoded for editing, plus the fidelity metadata needed to write it back byte-for-byte:
 * the [charset], whether it began with a byte-order mark ([hadBom]) and its dominant [lineEnding].
 * [text] is always normalized to `\n` line endings so the editor only ever deals with LF.
 */
data class DecodedText(
    val text: String,
    val charset: Charset,
    val hadBom: Boolean,
    val lineEnding: LineEnding,
)

/**
 * Pure encoding/line-ending detection so opening a file and saving it round-trips exactly.
 *
 * On open [decode] sniffs a BOM (UTF-8/UTF-16LE/UTF-16BE, else UTF-8), strips it, decodes, records
 * the dominant line ending and normalizes the buffer to `\n`. On save [encode] re-applies the
 * original line ending and BOM in the original charset. No Android dependencies, so it is unit-tested
 * directly on byte arrays.
 */
object TextEncoding {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Decodes [bytes] into editable text plus the metadata needed to re-encode it faithfully. */
    fun decode(bytes: ByteArray): DecodedText {
        val (charset, bomLen, hadBom) = when {
            bytes.startsWith(UTF8_BOM) -> Triple(Charsets.UTF_8, UTF8_BOM.size, true)
            bytes.startsWith(UTF16LE_BOM) -> Triple(Charsets.UTF_16LE, UTF16LE_BOM.size, true)
            bytes.startsWith(UTF16BE_BOM) -> Triple(Charsets.UTF_16BE, UTF16BE_BOM.size, true)
            else -> Triple(Charsets.UTF_8, 0, false)
        }
        val raw = String(bytes, bomLen, bytes.size - bomLen, charset)
        val lineEnding = detectLineEnding(raw)
        return DecodedText(normalizeToLf(raw), charset, hadBom, lineEnding)
    }

    /** Re-applies [lineEnding] and, when [hadBom], the byte-order mark, encoding with [charset]. */
    fun encode(text: String, charset: Charset, lineEnding: LineEnding, hadBom: Boolean): ByteArray {
        val normalized = normalizeToLf(text)
        val withEnding =
            if (lineEnding == LineEnding.CRLF) normalized.replace("\n", "\r\n") else normalized
        val body = withEnding.toByteArray(charset)
        val bom = if (hadBom) bomFor(charset) else ByteArray(0)
        return bom + body
    }

    /** The dominant line ending in [text]: CRLF only when CRLFs are present and not outnumbered. */
    fun detectLineEnding(text: String): LineEnding {
        var crlf = 0
        var lf = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                crlf++
                i += 2
            } else {
                if (c == '\n') lf++
                i++
            }
        }
        return if (crlf > 0 && crlf >= lf) LineEnding.CRLF else LineEnding.LF
    }

    /**
     * Collapses CRLF and lone CR to LF so the editor buffer is always `\n`-terminated. Returns the
     * input untouched when there is no CR at all, which is the common case: the two `replace` calls
     * each copy the whole document, so on a large file they were the bulk of the open cost.
     */
    fun normalizeToLf(text: String): String =
        if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

    private fun bomFor(charset: Charset): ByteArray = when (charset) {
        Charsets.UTF_8 -> UTF8_BOM
        Charsets.UTF_16LE -> UTF16LE_BOM
        Charsets.UTF_16BE -> UTF16BE_BOM
        else -> ByteArray(0)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
