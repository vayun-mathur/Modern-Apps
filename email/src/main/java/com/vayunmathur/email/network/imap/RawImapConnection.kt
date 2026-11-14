package com.vayunmathur.email.network.imap

import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.platform.ServerConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Raw IMAP connection using Socket / SSLSocket.
 * Implements RFC 3501 + STARTTLS + AUTH (PLAIN/LOGIN/XOAUTH2) + IDLE (RFC 2177).
 *
 * Literal handling: {N} at end of line indicates N raw bytes follow.
 *
 * Wire I/O lives in [ImapWireIo] and the FETCH/STORE pipeline in
 * [ImapFetchPipeline] (see `fetch`); this class keeps connection lifecycle,
 * auth, mailbox selection and IDLE.
 */

data class ImapCapabilities(val caps: Set<String>) {
    fun has(cap: String): Boolean = caps.contains(cap.uppercase())
}

/** One mailbox entry returned by an IMAP LIST response. */
data class ImapListEntry(val flags: List<String>, val delimiter: String?, val mailbox: String)

/** Result of selecting a mailbox, including its message counts and UID validity. */
data class ImapSelectResult(
    val exists: Int,
    val recent: Int = 0,
    val uidValidity: Long = 0,
    val uidNext: Long = 0,
    val flags: List<String> = emptyList(),
)

/** One message result returned by an IMAP FETCH response. */
data class ImapFetchResult(
    val uid: Long,
    val flags: List<String>,
    val internalDate: String?,
    val headerBytes: ByteArray?,
    val bodyBytes: ByteArray?,
    val size: Int = 0,
)

/** A raw socket-level IMAP connection to a single mail server. */
class RawImapConnection(
    val server: ServerConfig,
    val trustAll: Boolean = false,
) : AutoCloseable {

    /** Holds socket timeout and buffer constants for IMAP connections. */
    companion object {
        private const val TAG = "RawImap"
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_LINE = 8192 * 16
    }

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    internal var tagCounter = 1
    private var inIdle = false

    private fun inputStream(): InputStream = input ?: throw IOException("Not connected")
    private fun outputStream(): OutputStream = output ?: throw IOException("Not connected")

    private val wire = ImapWireIo(
        input = { inputStream() },
        onSendLine = { line -> writeLine(line) },
        maxLine = MAX_LINE,
    )

    /** FETCH/STORE/EXPUNGE pipeline over [wire]. */
    internal val fetch: ImapFetchPipeline = ImapFetchPipeline(wire, ::nextTag)

    internal fun nextTag(): String {
        val t = "A%04d".format(tagCounter)
        tagCounter++
        return t
    }

    private fun readLineWithLiteral(): Pair<String, ByteArray?>? = wire.readLineWithLiteral()

    fun connect() {
        Log.dev(TAG, "Connecting to ${server.host}:${server.port} ssl=${server.useSsl} trustAll=$trustAll")
        val s: Socket = if (server.useSsl) {
            TrustAll.createSocket(server.host, server.port, trustAll)
        } else {
            TrustAll.createPlainSocket(server.host, server.port)
        }
        s.soTimeout = SOCKET_TIMEOUT_MS
        socket = s
        if (s is SSLSocket) {
            sslSocket = s
        }
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())

        val greeting = readLineWithLiteral()?.first ?: ""
        Log.dev(TAG, "Greeting: $greeting")
        if (greeting.startsWith("* BYE") || greeting.startsWith("* BAD")) {
            throw IOException("IMAP server rejected: $greeting")
        }
    }

    fun startTls() {
        val plain = socket ?: throw IOException("Not connected for STARTTLS")
        val tag = nextTag()
        sendLine("$tag STARTTLS")
        val (resp, _) = readResponse(tag)
        if (!resp.uppercase().contains("OK")) throw IOException("STARTTLS failed: $resp")
        val upgraded = TrustAll.upgradeToTls(plain, server.host, server.port, trustAll, false)
        sslSocket = upgraded
        socket = upgraded
        upgraded.soTimeout = SOCKET_TIMEOUT_MS
        input = BufferedInputStream(upgraded.inputStream)
        output = BufferedOutputStream(upgraded.outputStream)
        Log.dev(TAG, "STARTTLS upgraded")
    }

    fun capability(): ImapCapabilities {
        val tag = nextTag()
        sendLine("$tag CAPABILITY")
        val lines = mutableListOf<String>()
        var last = ""
        var done = false
        while (!done) {
            val pair = readLineWithLiteral()
            if (pair == null) {
                done = true
            } else {
                val (line, _) = pair
                if (line.startsWith("* CAPABILITY")) lines.add(line)
                if (line.startsWith(tag)) {
                    last = line
                    done = true
                }
            }
        }
        val allCaps = lines.joinToString(" ")
            .uppercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
        Log.dev(TAG, "CAPABILITY $allCaps final=$last")
        return ImapCapabilities(allCaps)
    }

    fun login(user: String, pass: String): String {
        val tag = nextTag()
        sendLine("$tag LOGIN ${escapeString(user)} ${escapeString(pass)}")
        val (final, _) = readResponse(tag)
        if (!final.contains(" OK ")) throw ImapAuthException("LOGIN failed: $final")
        return final
    }

    fun authenticatePlain(user: String, pass: String): String {
        val authStr = "\u0000$user\u0000$pass"
        val b64 = Base64.encodeToString(authStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tag = nextTag()
        sendLine("$tag AUTHENTICATE PLAIN $b64")
        val (final, _) = readResponse(tag)
        if (!final.contains(" OK ")) throw ImapAuthException("AUTH PLAIN failed: $final")
        return final
    }

    fun authenticateLogin(user: String, pass: String): String {
        val tag = nextTag()
        sendLine("$tag AUTHENTICATE LOGIN")
        var cont = readLineWithLiteral()?.first ?: ""
        requireContinuation(cont, "AUTH LOGIN continuation")
        sendLine(Base64.encodeToString(user.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        cont = readLineWithLiteral()?.first ?: ""
        requireContinuation(cont, "AUTH LOGIN 2nd continuation")
        sendLine(Base64.encodeToString(pass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val (final, _) = readResponse(tag)
        if (!final.contains(" OK ")) throw ImapAuthException("AUTH LOGIN failed: $final")
        return final
    }

    fun authenticateXoauth2(email: String, token: String): String {
        val sasl = "user=$email\u0001auth=Bearer $token\u0001\u0001"
        val b64 = Base64.encodeToString(sasl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // Try inline first
        var tag = nextTag()
        sendLine("$tag AUTHENTICATE XOAUTH2 $b64")
        var accum = readUntilTag(tag)
        if (accum.finalLine.contains(" OK ", ignoreCase = true)) return accum.finalLine

        // If BAD/NO, retry challenge/response variant
        Log.dev(TAG, "XOAUTH2 inline failed: ${accum.finalLine}, trying CR")
        tag = nextTag()
        sendLine("$tag AUTHENTICATE XOAUTH2")
        val continuation = readLineWithLiteral()?.first ?: ""
        if (!continuation.startsWith("+")) {
            val (finalAfterCont, _) = readResponse(tag)
            throw ImapAuthException("XOAUTH2 CR failed: $continuation / $finalAfterCont")
        }
        sendLine(b64)
        accum = readUntilTag(tag)
        if (!accum.finalLine.contains(" OK ", ignoreCase = true)) {
            throw ImapAuthException("XOAUTH2 failed: ${accum.finalLine}")
        }
        return accum.finalLine
    }

    fun list(ref: String, pattern: String): List<ImapListEntry> {
        return mailboxOps.list(ref, pattern)
    }

    fun select(mailbox: String): ImapSelectResult {
        val tag = nextTag()
        sendLine("$tag SELECT ${escapeString(mailbox)}")
        return mailboxOps.collectSelect(tag, mailbox, "SELECT")
    }

    fun examine(mailbox: String): ImapSelectResult {
        return mailboxOps.examine(mailbox)
    }

    private val mailboxOps: MailboxOps by lazy {
        MailboxOps(
            nextTag = ::nextTag,
            sendLine = ::sendLine,
            readLine = { readLineWithLiteral() },
        )
    }

    private class MailboxOps(
        private val nextTag: () -> String,
        private val sendLine: (String) -> Unit,
        private val readLine: () -> Pair<String, ByteArray?>?,
    ) {
        fun list(ref: String, pattern: String): List<ImapListEntry> {
            val tag = nextTag()
            sendLine("$tag LIST ${escapeString(ref)} ${escapeString(pattern)}")
            return collectList(tag)
        }

        private fun collectList(tag: String): List<ImapListEntry> {
            val entries = mutableListOf<ImapListEntry>()
            var done = false
            while (!done) {
                val pair = readLine()
                if (pair == null) {
                    done = true
                } else {
                    val (line, literal) = pair
                    absorbListLine(line, literal, entries)
                    if (line.startsWith(tag)) done = true
                }
            }
            return entries
        }

        private fun absorbListLine(
            line: String,
            literal: ByteArray?,
            entries: MutableList<ImapListEntry>,
        ) {
            if (line.startsWith("* LIST")) {
                ImapParser.parseList(line)?.let { entries.add(it) }
            }
            if (literal != null && line.startsWith("* LIST")) {
                absorbListLiteral(line, literal, entries)
            }
        }

        private fun absorbListLiteral(
            line: String,
            literal: ByteArray,
            entries: MutableList<ImapListEntry>,
        ) {
            // Literal contains mailbox name (non-ASCII folder)
            val mbox = String(literal, Charsets.UTF_8)
            // The line before had placeholder; replace last entry's mailbox if needed
            // If parsing failed due to literal, we reconstruct
            if (entries.isEmpty() || !entries.last().mailbox.contains(mbox)) {
                // Try to parse flags/delim from line, use literal as mailbox
                val flags = ImapParser.extractListFlags(line)
                val delim = ImapParser.extractListDelimiter(line)
                val decodedMbox = ImapParser.decodeModifiedUtf7(mbox)
                entries.add(ImapListEntry(flags, delim, decodedMbox))
            }
        }

        fun examine(mailbox: String): ImapSelectResult {
            val tag = nextTag()
            sendLine("$tag EXAMINE ${escapeString(mailbox)}")
            var exists = 0
            var finalLine = ""
            var done = false
            while (!done) {
                val pair = readLine()
                if (pair == null) {
                    done = true
                } else {
                    val (line, _) = pair
                    Regex("""^\* (\d+) EXISTS""").find(line)?.let {
                        exists = it.groupValues[1].toIntOrNull() ?: exists
                    }
                    if (line.startsWith(tag)) {
                        finalLine = line
                        done = true
                    }
                }
            }
            if (!finalLine.uppercase().contains(" OK ")) throw IOException("EXAMINE $mailbox failed: $finalLine")
            return ImapSelectResult(exists)
        }

        fun collectSelect(tag: String, mailbox: String, op: String): ImapSelectResult {
            val state = SelectState()
            var finalLine = ""
            var done = false
            while (!done) {
                val pair = readLine()
                if (pair == null) {
                    done = true
                } else {
                    val (line, _) = pair
                    state.absorb(line)
                    if (line.startsWith(tag)) {
                        finalLine = line
                        done = true
                    }
                }
            }
            if (!finalLine.uppercase().contains(" OK ")) throw IOException("$op $mailbox failed: $finalLine")
            return state.toResult()
        }

        private fun escapeString(s: String): String {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        private class SelectState {
            var exists = 0
            var recent = 0
            var uidValidity = 0L
            var uidNext = 0L
            val flags = mutableListOf<String>()

            fun absorb(line: String) {
                Regex("""^\* (\d+) EXISTS""").find(line)?.let {
                    exists = it.groupValues[1].toIntOrNull() ?: exists
                }
                Regex("""^\* (\d+) RECENT""").find(line)?.let {
                    recent = it.groupValues[1].toIntOrNull() ?: recent
                }
                Regex("""\[UIDVALIDITY (\d+)\]""").find(line)?.let {
                    uidValidity = it.groupValues[1].toLongOrNull() ?: uidValidity
                }
                Regex("""\[UIDNEXT (\d+)\]""").find(line)?.let {
                    uidNext = it.groupValues[1].toLongOrNull() ?: uidNext
                }
                if (line.startsWith("* FLAGS")) flags.addAll(ImapParser.parseFlags(line))
            }

            fun toResult(): ImapSelectResult = ImapSelectResult(exists, recent, uidValidity, uidNext, flags)
        }
    }

    // ---- IDLE ----

    fun sendIdle(): String {
        val tag = nextTag()
        sendLine("$tag IDLE")
        val line = readLineWithLiteral()?.first ?: throw IOException("No response to IDLE")
        if (!line.startsWith("+")) throw IOException("IDLE continuation expected, got: $line")
        inIdle = true
        return tag
    }

    fun sendIdleDone() {
        if (!inIdle) return
        sendLine("DONE")
        inIdle = false
    }

    fun readIdleLine(): String? {
        return try { readLineWithLiteral()?.first } catch (_: Exception) { null }
    }

    fun readIdleResponseForTag(idleTag: String): String {
        val sb = StringBuilder()
        var done = false
        while (!done) {
            val pair = readLineWithLiteral()
            if (pair == null) {
                done = true
            } else {
                sb.appendLine(pair.first)
                if (pair.first.startsWith(idleTag)) done = true
            }
        }
        return sb.toString()
    }

    // ---- Low level ----

    private data class ResponseAccum(val lines: List<String>, val finalLine: String)

    private fun readUntilTag(tag: String): ResponseAccum {
        val lines = mutableListOf<String>()
        while (true) {
            val pair = readLineWithLiteral() ?: break
            lines.add(pair.first)
            if (pair.first.startsWith(tag)) return ResponseAccum(lines, pair.first)
        }
        return ResponseAccum(lines, lines.lastOrNull() ?: "")
    }

    private fun readResponse(tag: String): Pair<String, List<String>> {
        val lines = mutableListOf<String>()
        while (true) {
            val pair = readLineWithLiteral() ?: break
            lines.add(pair.first)
            if (pair.first.startsWith(tag)) return pair.first to lines
        }
        return (lines.lastOrNull() ?: "") to lines
    }

    internal fun sendLine(line: String) {
        writeLine(line)
    }

    private fun writeLine(line: String) {
        val out = outputStream()
        out.write((line + "\r\n").toByteArray(Charsets.US_ASCII))
        out.flush()
        val preview = if (line.contains("LOGIN") || line.contains("AUTHENTICATE") && line.contains("PLAIN")) {
            line.substringBefore(" ") + " [auth redacted]"
        } else {
            line
        }
        Log.dev(TAG, "C> $preview")
    }

    private fun escapeString(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun requireContinuation(line: String, op: String) {
        if (!line.startsWith("+")) throw IOException("$op failed: $line")
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { sslSocket?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
