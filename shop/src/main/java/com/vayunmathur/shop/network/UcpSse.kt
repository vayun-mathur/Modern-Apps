package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

internal data class UcpSseEvent(val event: String, val data: String)

/** Byte framing preserves split UTF-8 characters and supports LF, CRLF and CR. */
internal class UcpSse(private val stream: NetworkDataStream) {
    private val buffer = ByteArray(4096)
    private var position = 0
    private var available = 0
    private var total = 0
    private var events = 0
    private var skipLf = false

    private suspend fun byte(): Int {
        currentCoroutineContext().ensureActive()
        if (position == available) {
            available = stream.read(buffer)
            position = 0
            if (available < 0) return -1
            require(available > 0) { "SSE stream made no read progress" }
            total += available
            require(total <= UcpWire.MAX_BODY_BYTES) { "SSE body exceeds limit" }
        }
        return buffer[position++].toInt() and 255
    }

    private suspend fun line(): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = byte()
            if (skipLf) {
                skipLf = false
                if (b == 10) continue
            }
            if (b < 0) {
                require(out.size() == 0) { "Truncated SSE line" }
                return null
            }
            if (b == 13 || b == 10) {
                skipLf = b == 13
                return UcpWire.utf8(out.toByteArray())
            }
            require(out.size() < UcpWire.MAX_EVENT_BYTES) { "SSE line exceeds limit" }
            out.write(b)
        }
    }

    suspend fun next(): UcpSseEvent? {
        var event = "message"
        val data = StringBuilder()
        var size = 0
        var hasData = false
        while (true) {
            val line = line() ?: run {
                require(!hasData) { "Truncated SSE event" }
                return null
            }
            size += line.toByteArray(Charsets.UTF_8).size + 1
            require(size <= UcpWire.MAX_EVENT_BYTES) { "SSE event exceeds limit" }
            if (line.isEmpty()) {
                require(++events <= UcpWire.MAX_EVENTS) { "Too many SSE events" }
                if (hasData) return UcpSseEvent(event.ifEmpty { "message" }, data.toString().removeSuffix("\n"))
                event = "message"
                size = 0
                continue
            }
            if (line.startsWith(':')) continue
            val field = line.substringBefore(':')
            val value = if (line.contains(':')) line.substringAfter(':').removePrefix(" ") else ""
            when (field) {
                "event" -> event = value
                "data" -> { hasData = true; data.append(value).append('\n') }
            }
        }
    }
}
