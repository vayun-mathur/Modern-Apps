package com.vayunmathur.email.network.imap

import com.vayunmathur.library.log.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Low-level IMAP wire I/O: tagged command writes and line/literal reads.
 * Extracted from [RawImapConnection] to keep it under the function cap.
 * Behavior identical — only moved.
 */
internal class ImapWireIo(
    private val input: () -> InputStream,
    private val onSendLine: (String) -> Unit,
    private val maxLine: Int,
) {
    fun sendLine(line: String) {
        onSendLine(line)
    }

    fun readLineWithLiteral(): Pair<String, ByteArray?>? {
        val inp = input()
        val lineBytes = readLineBytes(inp) ?: return null
        val lineStr = String(lineBytes, Charsets.US_ASCII).trimEnd('\r', '\n')
        val litMatch = Regex("""\{(\d+)(\+)?\}$""").find(lineStr)
        if (litMatch != null) {
            val size = litMatch.groupValues[1].toIntOrNull() ?: 0
            if (size > MAX_LITERAL_BYTES) {
                // The literal length is server-controlled: allocating it
                // unchecked lets a broken or hostile server OOM the process.
                throw IOException("IMAP literal too large: $size bytes")
            }
            if (size > 0) {
                val litBytes = ByteArray(size)
                var read = 0
                while (read < size) {
                    val r = inp.read(litBytes, read, size - read)
                    if (r == -1) throw IOException("Unexpected EOF reading $size byte literal, got $read")
                    read += r
                }
                Log.dev(TAG, "S> [literal $size] line=${lineStr.take(LOG_LINE_PREVIEW_LEN)}")
                return lineStr to litBytes
            } else {
                return lineStr to ByteArray(0)
            }
        }
        if (lineStr.isNotBlank()) {
            val preview = if (lineStr.length > LOG_SNIFF_LEN) {
                lineStr.take(LOG_SNIFF_LEN) + " ... (${lineStr.length})"
            } else {
                lineStr
            }
            Log.dev(TAG, "S> $preview")
        }
        return lineStr to null
    }

    fun readLineBytes(inp: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        var count = 0
        var done = false
        var exhausted = false
        while (!done) {
            val b = inp.read()
            if (b == -1) {
                exhausted = baos.size() == 0
                done = true
            } else {
                baos.write(b)
                count++
                if (count > maxLine) {
                    drainToNewline(inp, baos)
                    done = true
                } else if (b == '\n'.code) {
                    done = true
                }
            }
        }
        if (exhausted) return null
        return baos.toByteArray()
    }

    private fun drainToNewline(inp: InputStream, baos: ByteArrayOutputStream) {
        Log.status(TAG, "Line exceeded MAX_LINE, draining to newline")
        var done = false
        while (!done) {
            val nb = inp.read()
            if (nb == -1) {
                done = true
            } else {
                baos.write(nb)
                if (nb == '\n'.code) done = true
            }
        }
    }

    companion object {
        private const val TAG = "RawImap"
        private const val LOG_LINE_PREVIEW_LEN = 120
        private const val LOG_SNIFF_LEN = 600

        /** Largest literal body buffered in memory; bigger mail fails the fetch instead of the process. */
        private const val MAX_LITERAL_BYTES = 64 * 1024 * 1024
    }
}
