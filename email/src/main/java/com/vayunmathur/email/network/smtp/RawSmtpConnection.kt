package com.vayunmathur.email.network.smtp

import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.email.network.imap.TrustAll
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Raw SMTP connection mirroring EmailManager SMTP flow via Socket/SSLSocket.
 * Handles EHLO, STARTTLS, AUTH PLAIN/LOGIN/XOAUTH2, MAIL FROM, RCPT TO, DATA, QUIT.
 */

class RawSmtpConnection(
    val server: ServerConfig,
    val trustAll: Boolean = false,
) : AutoCloseable {

    /** Holds socket timeout and buffer constants for SMTP connections. */
    companion object {
        private const val TAG = "RawSmtp"
        private const val TIMEOUT_MS = 30_000
        private const val SOCKET_BUFFER_SIZE = 8192
        private const val SMTP_REPLY_CODE_LEN = 3
        private const val SMTP_REPLY_LINE_MIN_LEN = 4
        private const val SMTP_REPLY_SEPARATOR_IDX = 3
        private const val SMTP_SUCCESS_MIN = 200
        private const val SMTP_SUCCESS_MAX = 299
        private const val SMTP_DATA_INTERMEDIATE = 354
    }

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var ehloResponse: List<String> = emptyList()
    private var caps: Set<String> = emptySet()

    fun connect() {
        Log.dev(TAG, "Connecting SMTP ${server.host}:${server.port} ssl=${server.useSsl}")
        val s: Socket = if (server.useSsl) {
            TrustAll.createSocket(server.host, server.port, trustAll || !TrustAll.isKnownHost(server.host))
        } else {
            TrustAll.createPlainSocket(server.host, server.port)
        }
        s.soTimeout = TIMEOUT_MS
        socket = s
        if (s is SSLSocket) sslSocket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), SOCKET_BUFFER_SIZE)
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), SOCKET_BUFFER_SIZE)

        val greeting = readResponse()
        if (!isPositive(greeting)) throw IOException("SMTP greeting failed: $greeting")
    }

    fun ehlo(hostname: String = "localhost"): List<String> {
        sendCommand("EHLO $hostname")
        val resp = readMultilineResponse()
        ehloResponse = resp
        caps = resp.map { it.drop(SMTP_REPLY_CODE_LEN).trim().uppercase() }.toSet()
        if (!isPositive(resp)) throw IOException("EHLO failed: $resp")
        return resp
    }

    fun startTls(host: String = server.host) {
        if (!hasCap("STARTTLS")) throw IOException("STARTTLS not advertised")
        sendCommand("STARTTLS")
        val resp = readResponse()
        checkSmtpPositive(resp, "STARTTLS")
        val plain = socket ?: throw IOException("No socket for STARTTLS")

        val upgraded = TrustAll.upgradeToTls(plain, host, server.port, trustAll || !TrustAll.isKnownHost(server.host))
        upgraded.soTimeout = TIMEOUT_MS
        sslSocket = upgraded
        socket = upgraded
        reader = BufferedReader(InputStreamReader(upgraded.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(upgraded.outputStream, Charsets.UTF_8))
    }

    fun authXoauth2(user: String, token: String) {
        val sasl = "user=$user\u0001auth=Bearer $token\u0001\u0001"
        val b64 = Base64.encodeToString(sasl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Try AUTH XOAUTH2 <b64> inline
        sendCommand("AUTH XOAUTH2 $b64")
        var resp = readResponse()
        if (isPositive(resp)) return

        // Fallback challenge/response: AUTH XOAUTH2 -> 334 -> send b64
        if (isContinuation(resp) || resp.any { it.startsWith("334") }) {
            // If server already sent 334 after inline? but our previous response was not positive — try CR variant
        }

        sendCommand("AUTH XOAUTH2")
        resp = readResponse()
        if (!resp.any { it.startsWith("334") }) {
            throw IOException("XOAUTH2 auth failed: $resp")
        }
        sendCommand(b64)
        resp = readResponse()
        if (!isPositive(resp)) throw IOException("XOAUTH2 auth failed: $resp")
    }

    fun authPlain(user: String, pass: String) {
        val str = "\u0000$user\u0000$pass"
        val b64 = Base64.encodeToString(str.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendCommand("AUTH PLAIN $b64")
        val resp = readResponse()
        if (!isPositive(resp)) throw IOException("AUTH PLAIN failed: $resp")
    }

    fun authLogin(user: String, pass: String) {
        sendCommand("AUTH LOGIN")
        var resp = readResponse()
        requireContinuation(resp, "AUTH LOGIN step1")
        sendCommand(Base64.encodeToString(user.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        resp = readResponse()
        requireContinuation(resp, "AUTH LOGIN step2")
        sendCommand(Base64.encodeToString(pass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        resp = readResponse()
        checkSmtpPositive(resp, "AUTH LOGIN")
    }

    fun mailFrom(address: String) {
        sendCommand("MAIL FROM:<$address>")
        val resp = readResponse()
        if (!isPositive(resp)) throw IOException("MAIL FROM failed: $resp")
    }

    fun rcptTo(address: String) {
        sendCommand("RCPT TO:<$address>")
        val resp = readResponse()
        if (!isPositive(resp)) throw IOException("RCPT TO <$address> failed: $resp")
    }

    fun data(mimeData: String) {
        sendCommand("DATA")
        val resp = readResponse()
        if (!resp.any { it.startsWith("354") }) throw IOException("DATA command failed: $resp")

        // Per RFC 5321, dot-stuffing: lines starting with . must be doubled
        val writer = writer ?: throw IOException("No writer")
        val lines = mimeData.split("\r\n", "\n")
        for (ln in lines) {
            val toSend = if (ln.startsWith(".")) ".$ln" else ln
            writer.write(toSend)
            writer.write("\r\n")
        }
        writer.write(".\r\n")
        writer.flush()

        val finalResp = readResponse()
        checkSmtpPositive(finalResp, "DATA final")
    }

    private fun checkSmtpPositive(resp: List<String>, op: String) {
        if (!isPositive(resp)) throw IOException("$op failed: $resp")
    }

    private fun requireContinuation(resp: List<String>, op: String) {
        if (!resp.any { it.startsWith("334") }) throw IOException("$op failed: $resp")
    }

    fun quit() {
        try {
            sendCommand("QUIT")
            readResponse()
        } catch (_: Exception) {}
    }

    fun hasCap(token: String): Boolean {
        val up = token.uppercase()
        return caps.any { it == up || it.startsWith("$up ") || it.contains(" $up") || it.contains(up) }
    }

    // ---- low level ----

    private fun sendCommand(cmd: String) {
        val redacted = when {
            cmd.startsWith("AUTH ") -> cmd.substringBefore(' ') + " [redacted]"
            else -> cmd
        }
        Log.dev(TAG, "C> $redacted")
        val w = writer ?: throw IOException("Not connected")
        w.write(cmd)
        w.write("\r\n")
        w.flush()
    }

    private fun readResponse(): List<String> {
        val r = reader ?: throw IOException("Not connected")
        val lines = mutableListOf<String>()
        var line: String? = r.readLine()
        while (line != null) {
            val current = line
            lines.add(current)
            Log.dev(TAG, "S> $current")
            // Multiline SMTP: 250- continues, 250 ends. Check separator char after the code.
            // If line[3] == '-' keep reading
            line = if (isLastReplyLine(current)) null else r.readLine()
        }
        return lines
    }

    private fun isLastReplyLine(line: String): Boolean {
        if (line.length < SMTP_REPLY_LINE_MIN_LEN) return true // malformed, stop
        return line[SMTP_REPLY_SEPARATOR_IDX] == ' '
    }

    private fun readMultilineResponse(): List<String> = readResponse()

    private fun isPositive(resp: List<String>): Boolean {
        val last = resp.lastOrNull() ?: return false
        if (last.length < SMTP_REPLY_CODE_LEN) return false
        val codeStr = last.substring(0, SMTP_REPLY_CODE_LEN)
        val code = codeStr.toIntOrNull() ?: return false
        // 2xx = positive completion, 354 = start mail input (intermediate positive for DATA)
        return (code in SMTP_SUCCESS_MIN..SMTP_SUCCESS_MAX) || code == SMTP_DATA_INTERMEDIATE
    }

    private fun isContinuation(resp: List<String>): Boolean {
        return resp.any { it.startsWith("334") }
    }

    override fun close() {
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { sslSocket?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
