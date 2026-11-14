package com.vayunmathur.library.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * HTTP for the Rust crates, over [NetworkClient].
 *
 * Keeping the transport here means native code inherits the app's proxy, cookie and TLS
 * behaviour, and no crate has to link its own copy of rustls (which cost ~1.3 MB per `.so`).
 *
 * The shape is chosen for being called from Rust in a loop rather than for looking idiomatic in
 * Kotlin. Everything crosses as primitives and two `ByteArray`s, so Rust performs one JNI call
 * and no object graph walking:
 *
 *  - **One static method.** Rust caches the class and method id once, so there is no `FindClass`
 *    per request.
 *  - **No result object.** The reply is a single packed `ByteArray`. The previous bridge returned
 *    a `NativeHttpResponse` whose four fields Rust then read back individually — four extra JNI
 *    round trips per request.
 *  - **No exceptions.** A failure is status 0 with the message as the body, so Rust never has to
 *    check for a pending exception between calls.
 *
 * Wire format is defined in `library/jni-http/src/main/rust/src/frame.rs`; the two must change
 * together. All integers are big-endian.
 *
 * ```text
 * headers (in and out)  repeated: u16 nameLen, name, u16 valueLen, value
 *
 * reply                 u16 status          (0 = the request never completed)
 *                       u32 urlLen,   final URL after redirects
 *                       u32 hdrLen,   header block as above
 *                       u32 bodyLen,  body bytes
 * ```
 */
object NativeHttpBridge {

    private const val GET = 0
    private const val POST = 1
    private const val HEAD = 2

    /** Wire-format field widths in bytes (see class KDoc). */
    private const val U16_BYTES = 2
    private const val U32_BYTES = 4
    private const val REPLY_PREFIX_BYTES = U16_BYTES + U32_BYTES + U32_BYTES + U32_BYTES
    private const val MAX_PAIR_BYTES = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val U32_BYTE_SHIFTS = 3

    /**
     * Upper bound on replies per batch, so a corrupt count cannot allocate a huge list.
     * A viewport's waves are dozens of ranges; thousands would be a bug, not a batch.
     */
    internal const val MAX_BATCH_REPLIES = 4096

    /**
     * Called from Rust. Never throws.
     *
     * @param method one of [GET], [POST], [HEAD].
     * @param headers packed request headers, may be empty.
     * @param body request body, or null.
     * @return the packed reply frame.
     */
    @JvmStatic
    fun request(method: Int, url: String, headers: ByteArray?, body: ByteArray?): ByteArray =
        executeRequest(method, url, headers, body)

    /**
     * A wave of GETs against one host in one JNI crossing. Never throws.
     *
     * Each range fetch is its own HTTP request (HTTP has no multi-range-across-requests), but
     * crossing once instead of N times drops N-1 thread-attach/detach cycles and N-1
     * `runBlocking` hops, and the N calls run concurrently inside one coroutine scope over the
     * shared pooled client — so a leaf wave plus a body wave costs two crossings, not 2N.
     * Order is preserved: reply `i` answers `ranges[i]`. A failed slot packs a status-0 frame,
     * exactly like [request]'s failure path, so per-slot errors stay per-slot.
     *
     * @param url the archive URL every range is read from.
     * @param ranges the `Range` header values, one per request.
     * @return u32 count followed by per-reply u32 length + reply frame (the [request] format).
     */
    @JvmStatic
    fun requestBatch(url: String, ranges: Array<String>): ByteArray =
        executeBatch(url, ranges)

    // Never-throws JNI boundary: any non-IO failure (interrupted runBlocking, packing bugs)
    // must still become a status-0 reply rather than a pending JNI exception.
    @Suppress("TooGenericExceptionCaught")
    private fun executeRequest(method: Int, url: String, headers: ByteArray?, body: ByteArray?): ByteArray {
        try {
            val response = runBlocking {
                NetworkClient.execute(
                    url = url,
                    method = when (method) {
                        POST -> "POST"
                        HEAD -> "HEAD"
                        else -> "GET"
                    },
                    headers = unpackHeaders(headers),
                    body = body,
                )
            }
            return packReply(response.status, response.url, response.headers, response.bytes)
        } catch (e: Exception) {
            return packFailure(url, e.message ?: e::class.simpleName ?: "request failed")
        }
    }

    /**
     * One crossing for a wave of range GETs: N concurrent `execute` calls inside one
     * `runBlocking`, over the shared pooled client, replies concatenated in order.
     *
     * `async`/`awaitAll` (not N sequential `execute` calls) is what makes this a wave rather
     * than N serial round trips: OkHttp's dispatcher multiplexes them over reused connections
     * (HTTP/2 where the server speaks it), so the wave costs ~1 round trip on a warm pool.
     */
    private fun executeBatch(url: String, ranges: Array<String>): ByteArray {
        try {
            val replies = runBlocking {
                val pending: List<kotlinx.coroutines.Deferred<ByteArray>> = ranges.map { range ->
                    async {
                        try {
                            val response = NetworkClient.execute(
                                url = url,
                                method = "GET",
                                headers = mapOf("Range" to range),
                                body = null,
                            )
                            packReply(response.status, response.url, response.headers, response.bytes)
                        } catch (e: java.io.IOException) {
                            packFailure(url, e.message ?: e::class.simpleName ?: "request failed")
                        } catch (e: IllegalArgumentException) {
                            packFailure(url, e.message ?: e::class.simpleName ?: "request failed")
                        }
                    }
                }
                pending.awaitAll()
            }
            return packBatch(replies)
        } catch (e: java.io.IOException) {
            // The batch framing itself failed: every slot reports the failure, so no slot hangs.
            return packBatch(ranges.map { packFailure(url, e.message ?: "batch failed") })
        }
    }

    private fun packFailure(url: String, reason: String): ByteArray {
        // Status 0 tells Rust the request never completed; the body carries the reason.
        return packReply(
            status = 0,
            url = url,
            headers = emptyMap(),
            body = reason.toByteArray(),
        )
    }

    /**
     * Concatenate per-reply frames behind a u32 count with u32 lengths, so Rust can split them
     * without parsing: `count, (len, frame) * count`, all big-endian.
     */
    private fun packBatch(replies: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeU32(out, replies.size)
        for (reply in replies) {
            writeU32(out, reply.size); out.write(reply)
        }
        return out.toByteArray()
    }

    /** Split [packBatch] output; null when truncated or the count disagrees with the frames. */
    internal fun unpackBatch(packed: ByteArray): List<ByteArray>? {
        if (packed.size < U32_BYTES) return null
        var pos = 0
        val count = readU32(packed, pos); pos += U32_BYTES
        if (count < 0 || count > MAX_BATCH_REPLIES) return null
        val out = ArrayList<ByteArray>(count)
        repeat(count) {
            if (pos + U32_BYTES > packed.size) return null
            val len = readU32(packed, pos); pos += U32_BYTES
            if (len < 0 || pos + len > packed.size) return null
            out.add(packed.copyOfRange(pos, pos + len))
            pos += len
        }
        if (pos != packed.size) return null
        return out
    }

    /** Packed pairs → the multimap [NetworkClient] expects. */
    private fun unpackHeaders(packed: ByteArray?): Map<String, List<String>> {
        if (packed == null || packed.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        val cursor = HeaderCursor(packed)
        while (true) {
            val (name, value) = cursor.nextPair() ?: break
            out.getOrPut(name) { mutableListOf() }.add(value)
        }
        return out
    }

    /** Cursor over length-prefixed pairs; null marks truncation or exhaustion. */
    private class HeaderCursor(private val packed: ByteArray) {
        private var pos = 0

        fun nextPair(): Pair<String, String>? {
            val name = nextField() ?: return null
            val value = nextField() ?: return null
            return name to value
        }

        private fun nextField(): String? {
            if (pos + U16_BYTES > packed.size) return null
            val len = readU16(packed, pos)
            pos += U16_BYTES
            if (len < 0 || pos + len > packed.size) return null
            val s = String(packed, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
    }

    private fun packReply(
        status: Int,
        url: String,
        headers: Map<String, List<String>>,
        body: ByteArray,
    ): ByteArray {
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val headerBytes = packHeaders(headers)
        val out = java.io.ByteArrayOutputStream(REPLY_PREFIX_BYTES + urlBytes.size + headerBytes.size + body.size)
        writeU16(out, status)
        writeU32(out, urlBytes.size); out.write(urlBytes)
        writeU32(out, headerBytes.size); out.write(headerBytes)
        writeU32(out, body.size); out.write(body)
        return out.toByteArray()
    }

    private fun packHeaders(headers: Map<String, List<String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((name, values) in headers) {
            packEntry(out, name, values)
        }
        return out.toByteArray()
    }

    private fun packEntry(
        out: java.io.ByteArrayOutputStream,
        name: String,
        values: List<String>,
    ) {
        // HttpURLConnection uses a null key for the status line; Rust has no use for it.
        if (name.isEmpty()) return
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_PAIR_BYTES) return
        packValues(out, nameBytes, values)
    }

    private fun packValues(
        out: java.io.ByteArrayOutputStream,
        nameBytes: ByteArray,
        values: List<String>,
    ) {
        for (value in values) {
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            if (valueBytes.size > MAX_PAIR_BYTES) continue
            writeU16(out, nameBytes.size); out.write(nameBytes)
            writeU16(out, valueBytes.size); out.write(valueBytes)
        }
    }

    private fun readU16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and BYTE_MASK) shl BYTE_SHIFT) or (b[at + 1].toInt() and BYTE_MASK)

    private fun readU32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and BYTE_MASK) shl (BYTE_SHIFT * U32_BYTE_SHIFTS)) or
            ((b[at + 1].toInt() and BYTE_MASK) shl (BYTE_SHIFT * 2)) or
            ((b[at + 2].toInt() and BYTE_MASK) shl BYTE_SHIFT) or
            (b[at + U32_BYTE_SHIFTS].toInt() and BYTE_MASK)

    private fun writeU16(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr BYTE_SHIFT) and BYTE_MASK)
        out.write(v and BYTE_MASK)
    }

    private fun writeU32(out: java.io.ByteArrayOutputStream, v: Int) {
        for (shift in U32_BYTE_SHIFTS downTo 0) {
            out.write((v ushr (shift * BYTE_SHIFT)) and BYTE_MASK)
        }
    }
}
