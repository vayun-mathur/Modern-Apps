package com.vayunmathur.email.network.imap

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.platform.EmlAttachment
import com.vayunmathur.email.platform.ParsedEml
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Minimal MIME parser replacing Jakarta Mail's MimeMessage walk.
 * Handles header unfolding, Content-Type params, CTE (base64/qp), RFC2047, charset, multipart split, CID cache.
 */
object MimeParser {

    private const val TAG = "MimeParser"
    private const val HEADER_SEPARATOR_CRLFCRLF_LEN = 4
    private const val HEADER_SEPARATOR_LFLF_LEN = 2
    private const val HEADER_SEPARATOR_CRLFLF_LEN = 3
    private const val CRLF_SECOND = 1
    private const val CRLF_THIRD = 2
    private const val CRLF_FOURTH = 3
    private const val QP_HEX_RADIX = 16
    private const val QP_HEX_PAIR_LEN = 3
    private const val QP_SOFT_BREAK_LEN = 3
    private const val QP_SOFT_BREAK_LF_LEN = 2
    private const val MAX_FILENAME_LEN = 120
    private const val HEADER_SNIFF_LEN = 1024
    private const val EMPTY_BODY_SIZE = 0

    /** A parsed MIME Content-Type header with its type, subtype and parameters. */
    data class ContentTypeInfo(val mainType: String, val subType: String, val params: Map<String, String>) {
        val fullType: String get() = "$mainType/$subType"
        val isMultipart: Boolean get() = mainType.equals("multipart", ignoreCase = true)
        val isText: Boolean get() = mainType.equals("text", ignoreCase = true)
        val isImage: Boolean get() = mainType.equals("image", ignoreCase = true)
    }

    /** A parsed MIME Content-Disposition header with its type and parameters. */
    data class DispositionInfo(val type: String, val params: Map<String, String>) {
        val isAttachment: Boolean get() = type.equals("attachment", ignoreCase = true)
        val isInline: Boolean get() = type.equals("inline", ignoreCase = true)
        val filename: String? get() = params["filename"] ?: params["name"]
    }

    /** One parsed MIME part with its headers, content type and decoded body data. */
    data class ParsedPart(
        val headers: Map<String, String>,
        val rawHeaderBlock: String,
        val contentType: ContentTypeInfo,
        val disposition: DispositionInfo?,
        val contentId: String?,
        val cte: String,
        val rawBodyBytes: ByteArray,
        val decodedBytes: ByteArray,
        val children: List<ParsedPart> = emptyList(),
        val partId: String = "",
        var bodyText: String? = null,
    )

    // ---- Public API ----

    fun parseMessage(
        rfc822Bytes: ByteArray,
        uid: Long,
        accountEmail: String,
        folderName: String,
        context: Context? = null,
    ): Triple<String?, Boolean, List<Attachment>> {
        val root = parsePart(rfc822Bytes, "")
        val triple = collectBodyAndAttachments(root, uid, accountEmail, folderName)
        if (context != null) {
            try { extractCidMapInternal(context, root, uid) } catch (_: Exception) {}
        }
        return triple
    }

    fun parseHeaderBlockBytes(headerBytes: ByteArray): Map<String, String> {
        val str = try {
            String(headerBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            String(headerBytes, Charsets.ISO_8859_1)
        }
        return parseHeadersString(str)
    }

    fun parsePart(rawPartBytes: ByteArray, partId: String): ParsedPart {
        return PartParser.parse(rawPartBytes, partId)
    }

    private object PartParser {
        fun parse(rawPartBytes: ByteArray, partId: String): ParsedPart {
            val (headerBytes, bodyBytes) = splitHeaderBody(rawPartBytes)
            val headerStr = String(headerBytes, Charsets.ISO_8859_1)
            val headers = parseHeadersString(headerStr)
            val ct = parseContentType(headers["content-type"])
            val disp = parseDisposition(headers["content-disposition"])
            val cte = (headers["content-transfer-encoding"] ?: "7bit").lowercase().trim()
            val cidRaw = headers["content-id"]?.let { extractCid(it) }

            if (ct.isMultipart) {
                return parseMultipart(headers, headerStr, ct, disp, cidRaw, cte, bodyBytes, partId)
            }

            val decoded = decodeCte(bodyBytes, cte)
            val bodyText = decodeTextBody(ct, decoded)

            return ParsedPart(
                headers,
                headerStr,
                ct,
                disp,
                cidRaw,
                cte,
                bodyBytes,
                decoded,
                emptyList(),
                partId,
                bodyText,
            )
        }

        private fun splitHeaderBody(rawPartBytes: ByteArray): Pair<ByteArray, ByteArray> {
            val sep = findHeaderBodySeparator(rawPartBytes)
                ?: return rawPartBytes to ByteArray(EMPTY_BODY_SIZE)
            val (sepStart, sepLen) = sep
            val headerBytes = rawPartBytes.copyOfRange(0, sepStart)
            val bodyBytes = if (sepStart + sepLen < rawPartBytes.size) {
                rawPartBytes.copyOfRange(sepStart + sepLen, rawPartBytes.size)
            } else {
                ByteArray(EMPTY_BODY_SIZE)
            }
            return headerBytes to bodyBytes
        }

        private fun parseMultipart(
            headers: Map<String, String>,
            headerStr: String,
            ct: ContentTypeInfo,
            disp: DispositionInfo?,
            cidRaw: String?,
            cte: String,
            bodyBytes: ByteArray,
            partId: String,
        ): ParsedPart {
            val boundary = ct.params["boundary"]
            if (boundary.isNullOrBlank()) {
                return ParsedPart(
                    headers,
                    headerStr,
                    ct,
                    disp,
                    cidRaw,
                    cte,
                    bodyBytes,
                    ByteArray(EMPTY_BODY_SIZE),
                    emptyList(),
                    partId,
                )
            }
            val subPartsBytes = splitMultipart(bodyBytes, boundary)
            val children = subPartsBytes.mapIndexed { idx, b ->
                val childId = if (partId.isEmpty()) idx.toString() else "$partId.$idx"
                parse(b, childId)
            }
            return ParsedPart(
                headers,
                headerStr,
                ct,
                disp,
                cidRaw,
                cte,
                bodyBytes,
                ByteArray(EMPTY_BODY_SIZE),
                children,
                partId,
            )
        }

        private fun decodeTextBody(ct: ContentTypeInfo, decoded: ByteArray): String? {
            if (!ct.isText) return null
            val charset = ct.params["charset"] ?: "utf-8"
            return decodeCharset(decoded, charset)
        }
    }

    fun collectBodyAndAttachments(
        root: ParsedPart,
        uid: Long,
        accountEmail: String,
        folderName: String,
    ): Triple<String?, Boolean, List<Attachment>> {
        val collector = BodyCollector(uid, accountEmail, folderName)
        collector.walk(root)
        return Triple(collector.body, collector.isHtml, collector.attachments)
    }


    fun parseEmlToParsedMessage(emlBytes: ByteArray, syntheticId: Long, context: Context): ParsedEml {
        val root = parsePart(emlBytes, "")
        val collector = EmlCollector(context, syntheticId)
        collector.walk(root)

        val headers = root.headers
        val from = headers["from"]?.let { decodeHeader(it) } ?: ""
        val subject = headers["subject"]?.let { decodeHeader(it) } ?: "(no subject)"
        val dateStr = headers["date"] ?: ""
        val dateMillis = parseDateToMillis(dateStr)
        val to = headers["to"]?.let { decodeHeader(it) }
        val cc = headers["cc"]?.let { decodeHeader(it) }
        val serverId = headers["message-id"]
        val refs = headers["references"] ?: headers["in-reply-to"]
        val listUnsub = headers["list-unsubscribe"]
        val listUnsubPost = headers["list-unsubscribe-post"]

        val emailMessage = com.vayunmathur.email.data.EmailMessage(
            accountEmail = "eml-viewer",
            folderName = "EML",
            id = syntheticId,
            serverId = serverId,
            threadId = syntheticId.toString(),
            subject = subject,
            from = from,
            to = to,
            cc = cc,
            date = dateStr,
            dateMillis = dateMillis,
            body = collector.body,
            isHtml = collector.isHtml,
            isRead = true,
            references = refs,
            hasAttachments = collector.attachments.isNotEmpty(),
            listUnsubscribe = listUnsub,
            listUnsubscribePost = listUnsubPost
        )
        return ParsedEml(emailMessage, collector.attachments, collector.cidMap)
    }

    private class EmlCollector(private val context: Context, private val syntheticId: Long) {
        var body: String? = null
        var isHtml = false
        val attachments = mutableListOf<EmlAttachment>()
        val cidMap = mutableMapOf<String, File>()

        fun walk(part: ParsedPart) {
            if (part.contentType.isMultipart) {
                part.children.forEach { walk(it) }
                return
            }
            if (absorbInlineImage(part)) return
            absorbAttachmentOrText(part)
        }

        private fun absorbInlineImage(part: ParsedPart): Boolean {
            val cid = part.contentId ?: return false
            if (!(part.contentType.isImage || part.disposition?.isInline == true)) return false
            val dir = File(context.cacheDir, "eml_cid/$syntheticId").also { it.mkdirs() }
            val rawName = part.disposition?.filename ?: "${cid.hashCode()}.bin"
            val safeName = rawName.replace(Regex("[/\\\\]"), "_").take(MAX_FILENAME_LEN).ifBlank {
                "${cid.hashCode()}.bin"
            }
            val out = File(dir, safeName)
            try {
                if (!out.exists()) out.writeBytes(part.decodedBytes)
                cidMap[cid] = out
            } catch (_: Exception) {}
            return true
        }

        private fun absorbAttachmentOrText(part: ParsedPart) {
            val filename = part.disposition?.filename ?: part.contentType.params["name"]
            if (!filename.isNullOrBlank() || part.disposition?.isAttachment == true) {
                val mime = part.contentType.fullType
                attachments.add(
                    EmlAttachment(
                        fileName = filename ?: "unnamed",
                        mimeType = mime,
                        bytes = part.decodedBytes,
                    ),
                )
                return
            }
            if (!part.contentType.isText) return
            val txt = part.bodyText ?: return
            if (body == null || (part.contentType.subType.equals("html", true) && !isHtml)) {
                body = txt
                isHtml = part.contentType.subType.equals("html", true)
            }
        }
    }

    fun extractCidMap(context: Context, rfc822Bytes: ByteArray, uid: Long): Map<String, File> {
        val root = parsePart(rfc822Bytes, "")
        return extractCidMapInternal(context, root, uid)
    }

    private fun extractCidMapInternal(context: Context, root: ParsedPart, uid: Long): Map<String, File> {
        val dir = File(context.cacheDir, "cid/$uid").also { it.mkdirs() }
        val collector = CidCollector(dir)
        collector.walk(root)
        collector.reconcileMetas()
        return collector.map
    }

    private class CidCollector(private val dir: File) {
        val map = mutableMapOf<String, File>()

        fun walk(part: ParsedPart) {
            if (part.contentType.isMultipart) {
                part.children.forEach { walk(it) }
                return
            }
            absorb(part)
        }

        private fun absorb(part: ParsedPart) {
            val cid = part.contentId ?: return
            if (cid.isBlank()) return
            if (!isInline(part)) return
            val safeName = (part.disposition?.filename ?: "${cid.hashCode()}.bin")
                .replace(Regex("[/\\\\]"), "_")
                .take(MAX_FILENAME_LEN)
                .ifBlank { "${cid.hashCode()}.bin" }
            val outFile = File(dir, safeName)
            if (!outFile.exists()) {
                try { outFile.writeBytes(part.decodedBytes) } catch (_: Exception) {}
            }
            if (outFile.exists()) {
                map[cid] = outFile
                try { File(dir, "$cid.meta").writeText(outFile.name) } catch (_: Exception) {}
            }
        }

        private fun isInline(part: ParsedPart): Boolean {
            return part.contentType.isImage ||
                part.disposition?.isInline == true ||
                part.contentType.fullType.startsWith("image/", true)
        }

        fun reconcileMetas() {
            try {
                dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { reconcileOne(it) }
            } catch (_: Exception) {}
        }

        private fun reconcileOne(meta: File) {
            val cid = meta.name.removeSuffix(".meta")
            if (cid in map) return
            val targetName = try { meta.readText().trim() } catch (_: Exception) { null } ?: return
            val file = File(dir, targetName)
            if (file.exists()) map[cid] = file
        }
    }

    // ---- Header / CT handling ----

    private object SeparatorMatcher {
        fun matchesCrlfCrlf(bytes: ByteArray, i: Int): Boolean {
            return bytes[i] == '\r'.code.toByte() &&
                bytes[i + CRLF_SECOND] == '\n'.code.toByte() &&
                bytes[i + CRLF_THIRD] == '\r'.code.toByte() &&
                bytes[i + CRLF_FOURTH] == '\n'.code.toByte()
        }

        fun matchesLfLf(bytes: ByteArray, i: Int): Boolean {
            return bytes[i] == '\n'.code.toByte() && bytes[i + CRLF_SECOND] == '\n'.code.toByte()
        }

        fun matchesCrlfLf(bytes: ByteArray, i: Int): Boolean {
            return bytes[i] == '\r'.code.toByte() &&
                bytes[i + CRLF_SECOND] == '\n'.code.toByte() &&
                bytes[i + CRLF_THIRD] == '\n'.code.toByte()
        }
    }

    private fun findHeaderBodySeparator(bytes: ByteArray): Pair<Int, Int>? {
        for (i in 0 until bytes.size - HEADER_SEPARATOR_CRLFCRLF_LEN + 1) {
            if (SeparatorMatcher.matchesCrlfCrlf(bytes, i)) {
                return Pair(i, HEADER_SEPARATOR_CRLFCRLF_LEN)
            }
        }
        for (i in 0 until bytes.size - HEADER_SEPARATOR_LFLF_LEN + 1) {
            if (SeparatorMatcher.matchesLfLf(bytes, i)) {
                return Pair(i, HEADER_SEPARATOR_LFLF_LEN)
            }
        }
        for (i in 0 until bytes.size - HEADER_SEPARATOR_CRLFLF_LEN + 1) {
            if (SeparatorMatcher.matchesCrlfLf(bytes, i)) {
                return Pair(i, HEADER_SEPARATOR_CRLFLF_LEN)
            }
        }
        return null
    }

    fun parseHeadersString(headerStr: String): Map<String, String> {
        val builder = HeaderBlockBuilder()
        val lines = headerStr.split("\r\n", "\n")
        for (rawLine in lines) {
            builder.absorb(rawLine)
        }
        return builder.build()
    }

    private class HeaderBlockBuilder {
        private val map = mutableMapOf<String, String>()
        private var currentKey: String? = null
        private var currentVal = StringBuilder()

        fun absorb(rawLine: String) {
            if (rawLine.isEmpty()) return
            val isContinuation = rawLine.firstOrNull()?.let { it == ' ' || it == '\t' } == true
            if (isContinuation && currentKey != null) {
                currentVal.append(' ').append(rawLine.trim())
            } else {
                absorbNewHeader(rawLine)
            }
        }

        private fun absorbNewHeader(rawLine: String) {
            val key = currentKey
            if (key != null) {
                map[key.lowercase()] = currentVal.toString().trim()
            }
            val colonIdx = rawLine.indexOf(':')
            if (colonIdx == -1) {
                currentKey = null
                currentVal = StringBuilder()
                return
            }
            currentKey = rawLine.substring(0, colonIdx).trim()
            currentVal = StringBuilder(rawLine.substring(colonIdx + 1).trim())
        }

        fun build(): Map<String, String> {
            val key = currentKey
            if (key != null) {
                map[key.lowercase()] = currentVal.toString().trim()
            }
            return map
        }
    }

    fun parseContentType(raw: String?): ContentTypeInfo {
        if (raw.isNullOrBlank()) return ContentTypeInfo("text", "plain", emptyMap())
        val semi = raw.indexOf(';')
        val typePart = if (semi == -1) raw.trim() else raw.substring(0, semi).trim()
        val slash = typePart.indexOf('/')
        val main = if (slash == -1) typePart else typePart.substring(0, slash).trim()
        val sub = if (slash == -1) "plain" else typePart.substring(slash + 1).trim()
        val params = mutableMapOf<String, String>()
        if (semi != -1) params.putAll(parseHeaderParams(raw.substring(semi + 1)))
        return ContentTypeInfo(main.lowercase(), sub.lowercase(), params.mapKeys { it.key.lowercase() })
    }

    fun parseDisposition(raw: String?): DispositionInfo? {
        if (raw.isNullOrBlank()) return null
        val semi = raw.indexOf(';')
        val type = if (semi == -1) raw.trim() else raw.substring(0, semi).trim()
        val params = mutableMapOf<String, String>()
        if (semi != -1) params.putAll(parseHeaderParams(raw.substring(semi + 1)))
        return DispositionInfo(type.lowercase(), params.mapKeys { it.key.lowercase() })
    }

    private fun parseHeaderParams(paramStr: String): Map<String, String> {
        val parser = HeaderParamParser(paramStr)
        parser.parse()
        return parser.result
    }

    private class HeaderParamParser(private val paramStr: String) {
        val result = mutableMapOf<String, String>()
        private var i = 0
        private var keySb = StringBuilder()
        private var valSb = StringBuilder()
        private var inKey = true
        private var inQuotes = false
        private var currentKey = ""

        fun parse() {
            while (i < paramStr.length) {
                val c = paramStr[i]
                if (inKey) parseKeyChar(c) else parseValueChar(c)
                i++
            }
            if (!inKey && currentKey.isNotBlank()) {
                result[currentKey.trim()] = valSb.toString().trim().removeSurrounding("\"")
            }
        }

        private fun parseKeyChar(c: Char) {
            if (c == '=') {
                currentKey = keySb.toString().trim()
                keySb = StringBuilder()
                inKey = false
                valSb = StringBuilder()
            } else if (c == ';' && !inQuotes) {
                keySb = StringBuilder()
            } else {
                keySb.append(c)
            }
        }

        private fun parseValueChar(c: Char) {
            if (!inQuotes && c == '"') {
                inQuotes = true
            } else if (inQuotes && c == '"') {
                inQuotes = false
            } else if (!inQuotes && c == ';') {
                commitValue()
            } else {
                valSb.append(c)
            }
        }

        private fun commitValue() {
            val v = valSb.toString().trim().removeSurrounding("\"")
            if (currentKey.isNotBlank()) result[currentKey.trim()] = v
            currentKey = ""
            keySb = StringBuilder()
            valSb = StringBuilder()
            inKey = true
        }
    }

    fun extractCid(rawCidHeader: String): String? {
        val t = rawCidHeader.trim()
        if (t.isEmpty()) return null
        return t.removePrefix("<").removeSuffix(">").trim().ifBlank { null }
    }

    private fun decodeCte(bytes: ByteArray, cte: String): ByteArray {
        return when (cte) {
            "base64" -> try {
                val str = String(bytes, Charsets.US_ASCII).replace(Regex("\\s"), "")
                if (str.isEmpty()) ByteArray(0) else Base64.decode(str, Base64.DEFAULT)
            } catch (ignored: Exception) {
                Log.status(TAG, "base64 decode failed: ${ignored.message}")
                bytes
            }
            "quoted-printable" -> decodeQuotedPrintable(bytes)
            else -> bytes
        }
    }

    fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            i = QpDecoder.decodeNext(bytes, i, out)
        }
        return out.toByteArray()
    }

    private object QpDecoder {
        fun decodeNext(bytes: ByteArray, i: Int, out: ByteArrayOutputStream): Int {
            val b = bytes[i]
            if (b != '='.code.toByte()) {
                out.write(b.toInt())
                return i + 1
            }
            if (i + 1 >= bytes.size) return bytes.size
            val softBreak = softBreakLen(bytes, i)
            if (softBreak > 0) return i + softBreak
            return decodeHex(bytes, i, out)
        }

        private fun softBreakLen(bytes: ByteArray, i: Int): Int {
            val next = bytes[i + 1]
            if (next == '\r'.code.toByte()) {
                if (i + QP_SOFT_BREAK_LEN - 1 < bytes.size &&
                    bytes[i + QP_SOFT_BREAK_LEN - 1] == '\n'.code.toByte()
                ) {
                    return QP_HEX_PAIR_LEN
                }
                return 0
            }
            if (next == '\n'.code.toByte()) return QP_SOFT_BREAK_LF_LEN
            return 0
        }

        private fun decodeHex(bytes: ByteArray, i: Int, out: ByteArrayOutputStream): Int {
            if (i + QP_HEX_PAIR_LEN - 1 < bytes.size) {
                val h1 = bytes[i + 1].toInt().toChar()
                val h2 = bytes[i + 2].toInt().toChar()
                try {
                    out.write("$h1$h2".toInt(QP_HEX_RADIX))
                    return i + QP_HEX_PAIR_LEN
                } catch (_: Exception) {}
            }
            out.write(bytes[i].toInt())
            return i + 1
        }
    }

    fun decodeCharset(bytes: ByteArray, charsetName: String): String {
        val name = charsetName.trim().lowercase()
        val candidates = listOf(
            name,
            when (name) {
                "utf8" -> "utf-8"
                "latin1", "iso8859-1", "iso-8859-1" -> "iso-8859-1"
                "windows-1252", "cp1252" -> "windows-1252"
                "windows-1250", "cp1250" -> "windows-1250"
                "us-ascii", "ascii" -> "us-ascii"
                else -> name
            },
            "utf-8",
            "iso-8859-1"
        ).distinct()
        for (cs in candidates) {
            try {
                return String(bytes, Charset.forName(cs))
            } catch (_: Exception) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    private val rfc2047Pattern = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=", Pattern.CASE_INSENSITIVE)

    fun decodeHeader(raw: String): String {
        var result = raw
        try {
            val matcher = rfc2047Pattern.matcher(raw)
            val sb = StringBuffer()
            while (matcher.find()) {
                val charset = matcher.group(1) ?: "utf-8"
                val enc = matcher.group(2)?.uppercase() ?: "B"
                val encoded = matcher.group(3) ?: ""
                val decoded = try {
                    if (enc == "B") {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        decodeCharset(bytes, charset)
                    } else {
                        val qpStr = encoded.replace('_', ' ')
                        val decodedBytes = decodeQuotedPrintable(qpStr.toByteArray(Charsets.ISO_8859_1))
                        decodeCharset(decodedBytes, charset)
                    }
                } catch (_: Exception) { encoded }
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(decoded))
            }
            matcher.appendTail(sb)
            result = sb.toString()
        } catch (_: Exception) {}
        return result
    }

    fun parseDateToMillis(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss zzz",
            "EEE MMM dd HH:mm:ss Z yyyy",
            "EEE MMM dd HH:mm:ss zzz yyyy",
            "EEE MMM dd HH:mm:ss yyyy",
            "yyyy-MM-dd HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "dd-MMM-yyyy HH:mm:ss Z"
        )
        for (fmtStr in formats) {
            try {
                val fmt = java.text.SimpleDateFormat(fmtStr, java.util.Locale.US)
                fmt.isLenient = true
                val d = fmt.parse(dateStr.trim())
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        try {
            @Suppress("DEPRECATION")
            val d = java.util.Date(dateStr)
            if (d.time != 0L) return d.time
        } catch (_: Exception) {}
        return 0L
    }

    fun sanitizeFilename(name: String): String {
        var n = name.trim()
        if (n.contains("=?")) n = decodeHeader(n)
        n = n.substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[\\r\\n\"]"), "_")
        if (n.length > MAX_FILENAME_LEN) n = n.take(MAX_FILENAME_LEN)
        return n.ifBlank { "attachment" }
    }

    fun splitMultipart(bodyBytes: ByteArray, boundary: String): List<ByteArray> {
        if (boundary.isBlank()) return emptyList()
        val bodyStr = String(bodyBytes, Charsets.ISO_8859_1)
        val delim = "--$boundary"
        val positions = MultipartSplitter.scan(bodyStr, delim)

        if (positions.size < 2) {
            return MultipartSplitter.singleFallback(bodyStr, positions)
        }

        val result = mutableListOf<ByteArray>()
        for (i in 0 until positions.size - 1) {
            val cur = positions[i]
            if (cur.isClose) break
            MultipartSplitter.slice(bodyStr, cur, positions[i + 1])?.let { result.add(it) }
        }
        return result
    }

    private data class DelimPos(val start: Int, val end: Int, val isClose: Boolean)

    private object MultipartSplitter {
        fun scan(bodyStr: String, delim: String): List<DelimPos> {
            val positions = mutableListOf<DelimPos>()
            var searchFrom = 0
            var done = false
            while (!done) {
                val idx = bodyStr.indexOf(delim, searchFrom)
                if (idx == -1) {
                    done = true
                } else {
                    positions.add(scanOne(bodyStr, delim, idx))
                    if (positions.last().isClose) {
                        done = true
                    } else {
                        searchFrom = positions.last().end
                    }
                }
            }
            return positions
        }

        private fun scanOne(bodyStr: String, delim: String, idx: Int): DelimPos {
            var lineEnd = bodyStr.indexOf('\n', idx)
            if (lineEnd == -1) lineEnd = bodyStr.length else lineEnd++
            return DelimPos(idx, lineEnd, isCloseDelimiter(bodyStr, delim, idx))
        }

        private fun isCloseDelimiter(bodyStr: String, delim: String, idx: Int): Boolean {
            if (idx + delim.length >= bodyStr.length) return false
            val nl = bodyStr.indexOf('\n', idx)
            val afterDelimPart = if (nl == -1) {
                bodyStr.substring(idx + delim.length)
            } else {
                bodyStr.substring(idx + delim.length, nl)
            }
            return afterDelimPart.trim().startsWith("--")
        }

        fun singleFallback(bodyStr: String, positions: List<DelimPos>): List<ByteArray> {
            if (positions.size == 1 && !positions[0].isClose) {
                val start = positions[0].end
                if (start < bodyStr.length) {
                    return listOf(bodyStr.substring(start).toByteArray(Charsets.ISO_8859_1))
                }
            }
            return emptyList()
        }

        fun slice(bodyStr: String, cur: DelimPos, next: DelimPos): ByteArray? {
            val partStart = cur.end
            var partEnd = next.start
            if (partEnd >= 2 && bodyStr[partEnd - 1] == '\n') {
                partEnd--
                if (partEnd > 0 && bodyStr[partEnd - 1] == '\r') partEnd--
            }
            if (partStart > partEnd) return null
            return bodyStr.substring(partStart, partEnd).toByteArray(Charsets.ISO_8859_1)
        }
    }

    fun extractHeaderValue(headers: Map<String, String>, name: String): String? {
        return headers[name.lowercase()]?.let { decodeHeader(it) }
    }
}
