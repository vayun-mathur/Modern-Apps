package com.vayunmathur.cast.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Big enough that a range serve is a handful of writes, small enough to hold per connection. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * The HTTP half of the media proxy: how a request is read, checked and answered.
 *
 * The phone serves app media to the TV over HTTPS instead of re-encoding it into RTP, so this
 * is the wire format for that. It lives in `:cast:protocol` rather than beside the listener in
 * `:cast` for the same reason the handshake rules do: the rules have to be testable without a
 * device, and `android.util.Log` is not available to a JVM unit test. The listener in `:cast`
 * owns the sockets and the logging; everything that decides what bytes come back is here.
 *
 * Only what a player actually sends is implemented. `GET` and `HEAD`, one byte range at a time,
 * no chunked bodies and no redirects - ExoPlayer asks for nothing else, and a proxy that
 * answers requests nobody makes is a proxy with untested code paths in it.
 */
object MediaProxy {

    /**
     * The capability token is a path segment rather than a header because it has to survive
     * being handed to a player as a plain URL. `DefaultHttpDataSource` will carry default
     * request properties, but a manifest's `<BaseURL>` and a subtitle URL are just strings, and
     * a token in the path travels with them for free.
     */
    fun url(host: String, port: Int, token: String, resourceId: String): String =
        "https://$host:$port/$token/$resourceId"

    /** Longest request line or header line accepted, to bound what an unauthenticated peer can make us buffer. */
    const val MAX_LINE_BYTES = 8 * 1024

    /** Most header lines accepted, for the same reason. */
    const val MAX_HEADERS = 64

    /**
     * Compares tokens in constant time.
     *
     * A byte-at-a-time comparison leaks the length of the matching prefix, and the token is the
     * only thing standing between a LAN peer and the user's media. Length is compared first
     * because [MessageDigest.isEqual] short-circuits on mismatched lengths, which is fine: the
     * length is not the secret.
     */
    fun tokenMatches(expected: String, offered: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.US_ASCII),
        offered.toByteArray(Charsets.US_ASCII),
    )

    /**
     * A [MediaResource.length] below zero: still being written, final size unknown.
     *
     * A descriptor to a growing file reports EOF at the current end of file, and there is no way
     * to tell "not yet" from "done" by reading it - so the length is what the producer says, and
     * this is how it says it does not know yet.
     */
    const val UNKNOWN_LENGTH = -1L
}

/** A resource the proxy can serve: a length that may not be known yet, and a stream that can start part-way in. */
interface MediaResource {

    /**
     * Total length in bytes, or negative for [MediaProxy.UNKNOWN_LENGTH].
     *
     * Known up front even when the bytes are not all present yet - a SABR segment still on its
     * way down has a length from its index. That is why [open] is allowed to end early and why
     * the caller has to notice when it does.
     *
     * Negative is the stronger case: the producer does not know the final size either, because it
     * is still encoding. The proxy then states no `Content-Length`, honours no `Range`, and
     * expects [open]'s stream to wait at the current end of file rather than report it.
     */
    val length: Long

    /** Whether [length] can be stated to a client, and therefore whether a `Range` can be answered. */
    val hasKnownLength: Boolean get() = length >= 0

    val contentType: String

    /**
     * A stream positioned at [offset]. The caller closes it.
     *
     * For an unknown length the stream must block at the current end of file until either more
     * bytes arrive or the producer is finished, and signal a producer that stopped without
     * finishing by throwing [IOException] rather than by returning `-1`, which would be
     * indistinguishable from a clean end.
     */
    fun open(offset: Long): InputStream
}

/** Maps the resource id in a request path to something servable, or null if there is no such resource. */
fun interface MediaResourceResolver {
    fun resolve(resourceId: String): MediaResource?
}

/** What a byte range in a request turned out to mean. */
sealed interface RangeSpec {

    /** No `Range` header, or one this proxy chooses not to honour. The whole resource, `200`. */
    object Whole : RangeSpec

    /** Both ends inclusive, as HTTP counts them. */
    data class Satisfiable(val first: Long, val last: Long) : RangeSpec {
        val length: Long get() = last - first + 1
    }

    /** Syntactically fine but outside the resource, which is a `416` rather than a clamp. */
    object Unsatisfiable : RangeSpec
}

/** How an exchange ended, for the listener to log. */
sealed interface ExchangeOutcome {

    data class Served(val resourceId: String, val range: RangeSpec, val bytes: Long) : ExchangeOutcome

    /** Answered with an error status. [detail] is for the log, never for the client. */
    data class Rejected(val status: Int, val detail: String) : ExchangeOutcome

    /**
     * Fewer bytes arrived from the resource than its length promised.
     *
     * The response has already been committed with a `Content-Length` by then, so there is no
     * way to turn this into an error status; the connection must be closed so the client sees a
     * truncated body instead of waiting for bytes that are not coming.
     *
     * [expected] is [MediaProxy.UNKNOWN_LENGTH] when the resource never had a length to fall
     * short of and its producer stopped without finishing - a transcode that died part-way. The
     * answer is the same either way, because a closed connection is the only signal left.
     */
    data class Truncated(val resourceId: String, val expected: Long, val actual: Long) : ExchangeOutcome

    /** The peer closed before sending a request. Ordinary at the end of a keep-alive connection. */
    object Closed : ExchangeOutcome
}

/**
 * Serves requests for one connection.
 *
 * Keep-alive is supported deliberately. Scrubbing turns into a burst of range requests, and a
 * fresh TLS handshake for each one would put the cost of a seek back into the same place the
 * RTP path had it.
 */
class MediaProxyExchange(
    private val token: String,
    private val resolver: MediaResourceResolver,
) {

    /**
     * Reads one request and writes one response.
     *
     * Returns whether the connection may be reused, alongside the outcome. Anything that leaves
     * the stream in an unknown state - a malformed request, a truncated body - ends the
     * connection, because the alternative is misreading the next request off a desynchronised
     * stream.
     */
    fun serve(input: InputStream, output: OutputStream): Result {
        when (val request = readRequest(input)) {
            is IncomingRequest.Closed -> return closedResult()
            is IncomingRequest.Malformed -> return malformedRequest(output)
            is IncomingRequest.Parsed -> {
                val headers = readHeaders(input) ?: return headersTooLarge(output)
                return dispatch(request.request, headers, output)
            }
        }
    }

    private sealed interface IncomingRequest {
        data object Closed : IncomingRequest
        data object Malformed : IncomingRequest
        data class Parsed(val request: ParsedRequest) : IncomingRequest
    }

    private data class ParsedRequest(val method: String, val target: String)

    private fun closedResult(): Result = Result(ExchangeOutcome.Closed, reusable = false)

    private fun headersTooLarge(output: OutputStream): Result {
        respondError(output, HTTP_HEADERS_TOO_LARGE)
        return Result(
            ExchangeOutcome.Rejected(HTTP_HEADERS_TOO_LARGE, "too many or too long headers"),
            reusable = false,
        )
    }

    private fun readRequest(input: InputStream): IncomingRequest {
        val requestLine = readLine(input) ?: return IncomingRequest.Closed
        val parts = requestLine.split(' ')
        if (parts.size != REQUEST_PART_COUNT || !parts[2].startsWith("HTTP/1.")) {
            return IncomingRequest.Malformed
        }
        return IncomingRequest.Parsed(ParsedRequest(method = parts[0], target = parts[1]))
    }

    private fun dispatch(request: ParsedRequest, headers: Map<String, String>, output: OutputStream): Result {
        if (request.method != "GET" && request.method != "HEAD") {
            return methodNotAllowed(output, request.method)
        }
        val path = parsePath(request.target)
        if (path == null || !MediaProxy.tokenMatches(token, path.token)) {
            return forbidden(output, request.target)
        }
        val resource = resolver.resolve(path.resourceId) ?: return notFound(output, path.resourceId)
        // Before the `Range` is even parsed: with no total there is nothing to interpret one
        // against, and `parseRange` would call every range unsatisfiable and answer `416`.
        if (!resource.hasKnownLength) {
            return serveUnknownLength(path.resourceId, resource, request.method, output)
        }
        return serveKnownLength(path.resourceId, resource, request.method, headers["range"], output)
    }

    private fun malformedRequest(output: OutputStream): Result {
        respondError(output, HTTP_BAD_REQUEST)
        return Result(ExchangeOutcome.Rejected(HTTP_BAD_REQUEST, "malformed request line"), reusable = false)
    }

    private fun methodNotAllowed(output: OutputStream, method: String): Result {
        respondError(output, HTTP_METHOD_NOT_ALLOWED)
        return Result(ExchangeOutcome.Rejected(HTTP_METHOD_NOT_ALLOWED, "method $method"), reusable = true)
    }

    private fun forbidden(output: OutputStream, target: String): Result {
        // Deliberately the same answer for a bad token and an unparseable path: telling a
        // peer which of the two it got wrong tells it the shape of a valid URL.
        respondError(output, HTTP_FORBIDDEN)
        return Result(ExchangeOutcome.Rejected(HTTP_FORBIDDEN, "bad token or path '$target'"), reusable = true)
    }

    private fun notFound(output: OutputStream, resourceId: String): Result {
        respondError(output, HTTP_NOT_FOUND)
        return Result(ExchangeOutcome.Rejected(HTTP_NOT_FOUND, "no resource '$resourceId'"), reusable = true)
    }

    private fun rangeNotSatisfiable(output: OutputStream, length: Long): Result {
        respondError(
            output,
            HTTP_RANGE_NOT_SATISFIABLE,
            extraHeaders = listOf("Content-Range: bytes */$length"),
        )
        return Result(
            ExchangeOutcome.Rejected(HTTP_RANGE_NOT_SATISFIABLE, "range outside $length"),
            reusable = true,
        )
    }

    private fun serveKnownLength(
        resourceId: String,
        resource: MediaResource,
        method: String,
        rangeHeader: String?,
        output: OutputStream,
    ): Result {
        val range = parseRange(rangeHeader, resource.length)
        if (range is RangeSpec.Unsatisfiable) {
            return rangeNotSatisfiable(output, resource.length)
        }
        writeHead(resource, range, output)
        if (method == "HEAD") {
            output.flush()
            return Result(ExchangeOutcome.Served(resourceId, range, 0), reusable = true)
        }
        val first = if (range is RangeSpec.Satisfiable) range.first else 0L
        val bodyLength = if (range is RangeSpec.Satisfiable) range.length else resource.length
        val written = resource.open(first).use { body -> copy(body, output, bodyLength) }
        output.flush()

        return if (written < bodyLength) {
            Result(
                ExchangeOutcome.Truncated(resourceId, bodyLength, written),
                reusable = false,
            )
        } else {
            Result(ExchangeOutcome.Served(resourceId, range, written), reusable = true)
        }
    }

    private fun writeHead(resource: MediaResource, range: RangeSpec, output: OutputStream) {
        val bodyLength = if (range is RangeSpec.Satisfiable) range.length else resource.length
        val head = StringBuilder()
        head.append(
            if (range is RangeSpec.Satisfiable) {
                "HTTP/1.1 206 Partial Content\r\n"
            } else {
                "HTTP/1.1 200 OK\r\n"
            },
        )
        head.append("Content-Type: ").append(resource.contentType).append("\r\n")
        head.append("Content-Length: ").append(bodyLength).append("\r\n")
        // Without this ExoPlayer assumes the server cannot seek and refuses to scrub at all.
        head.append("Accept-Ranges: bytes\r\n")
        if (range is RangeSpec.Satisfiable) {
            head.append("Content-Range: bytes ")
                .append(range.first).append('-').append(range.last)
                .append('/').append(resource.length).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }

    data class Result(val outcome: ExchangeOutcome, val reusable: Boolean)

    /**
     * Answers a resource whose final size nobody knows yet.
     *
     * `200`, no `Content-Length`, and the body delimited by closing the connection - which
     * HTTP/1.1 permits and `HttpURLConnection` reads as an unset length. `Accept-Ranges` is
     * omitted rather than advertised, because there is no total to answer a `Range` against.
     *
     * **A `Range` on such a resource is ignored and the whole thing is served**, which is the
     * answer the multi-range branch already establishes for a range this proxy declines. The
     * consequence is worth stating plainly: the first play of a resource still being written
     * cannot be seeked. The alternative is predicting the final size of a VBR stream before
     * encoding it, which cannot be done exactly, and being wrong shows up as a seek bar that
     * lies or a body cut short.
     */
    private fun serveUnknownLength(
        resourceId: String,
        resource: MediaResource,
        method: String,
        output: OutputStream,
    ): Result {
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(resource.contentType).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))

        if (method == "HEAD") {
            output.flush()
            // Not reusable even though no body was sent: the client has been told this connection
            // closes, and answering a second request down it would contradict that.
            return Result(ExchangeOutcome.Served(resourceId, RangeSpec.Whole, 0), reusable = false)
        }

        val transfer = resource.open(0).use { body -> copyUntilEnd(body, output) }
        output.flush()
        return if (transfer.complete) {
            Result(ExchangeOutcome.Served(resourceId, RangeSpec.Whole, transfer.written), reusable = false)
        } else {
            Result(
                ExchangeOutcome.Truncated(resourceId, MediaProxy.UNKNOWN_LENGTH, transfer.written),
                reusable = false,
            )
        }
    }

    // ------------------------------------------------------------------
    // Request reading
    // ------------------------------------------------------------------

    /**
     * Reads a CRLF-terminated line a byte at a time.
     *
     * Byte at a time rather than a buffered reader because the body must be left untouched for
     * [copy] - and on a keep-alive connection, over-reading past the headers would swallow the
     * start of the next request.
     */
    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (out.isEmpty()) null else out.toString()
            if (byte == '\n'.code) {
                if (out.isNotEmpty() && out.last() == '\r') out.setLength(out.length - 1)
                return out.toString()
            }
            if (out.length >= MediaProxy.MAX_LINE_BYTES) return null
            out.append(byte.toChar())
        }
    }

    /** Header names lowercased, because HTTP does not promise a case and clients do not agree on one. */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return headers
            if (headers.size >= MediaProxy.MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    private fun copy(from: InputStream, to: OutputStream, limit: Long): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = limit
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = from.read(buffer, 0, want)
            if (read == -1) break
            to.write(buffer, 0, read)
            remaining -= read
        }
        return limit - remaining
    }

    /**
     * Copies until the resource ends, with no length to stop at.
     *
     * The distinction the return value carries is the whole point. A stream over a resource still
     * being written waits at the current end of file, so `-1` means the producer genuinely
     * finished; a producer that died is expected to throw instead, because a `-1` for that case
     * would be indistinguishable from success and would serve a silently half-length track.
     */
    private fun copyUntilEnd(from: InputStream, to: OutputStream): Transfer {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = try {
                from.read(buffer, 0, buffer.size)
            } catch (_: IOException) {
                return Transfer(written, complete = false)
            }
            if (read == -1) return Transfer(written, complete = true)
            to.write(buffer, 0, read)
            written += read
        }
    }

    /** How much of an unknown-length body was sent, and whether it was all of it. */
    private class Transfer(val written: Long, val complete: Boolean)

    private fun respondError(output: OutputStream, status: Int, extraHeaders: List<String> = emptyList()) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        head.append("Content-Length: 0\r\n")
        extraHeaders.forEach { head.append(it).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        HTTP_BAD_REQUEST -> "Bad Request"
        HTTP_FORBIDDEN -> "Forbidden"
        HTTP_NOT_FOUND -> "Not Found"
        HTTP_METHOD_NOT_ALLOWED -> "Method Not Allowed"
        HTTP_RANGE_NOT_SATISFIABLE -> "Range Not Satisfiable"
        HTTP_HEADERS_TOO_LARGE -> "Request Header Fields Too Large"
        else -> "Error"
    }

    companion object {
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_HEADERS_TOO_LARGE = 431
        private const val REQUEST_PART_COUNT = 3
        private const val PERCENT_HEX_DIGITS = 3
        private const val HEX_RADIX = 16

        data class ProxyPath(val token: String, val resourceId: String)

        /**
         * Splits `/<token>/<resourceId>` and drops any query string.
         *
         * The resource id may itself contain slashes: a SABR segment is naturally addressed as
         * `<itag>/<sequence>`, so everything after the token is the id.
         */
        fun parsePath(target: String): ProxyPath? {
            val withoutQuery = target.substringBefore('?')
            if (!withoutQuery.startsWith('/')) return null
            val rest = withoutQuery.substring(1)
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            return ProxyPath(
                token = percentDecode(rest.substring(0, slash)),
                resourceId = percentDecode(rest.substring(slash + 1)),
            )
        }

        /**
         * Interprets a `Range` header against a known [length].
         *
         * Multi-range requests are answered with the whole resource rather than a multipart
         * body. HTTP permits ignoring a `Range` you do not want to honour, ExoPlayer never asks
         * for more than one range, and a multipart encoder would be code with no caller.
         */
        fun parseRange(header: String?, length: Long): RangeSpec {
            val spec = extractSingleRangeSpec(header) ?: return rangeFallback(header, length)
            return resolveRange(spec, length)
        }

        private fun rangeFallback(header: String?, length: Long): RangeSpec {
            if (header == null) return RangeSpec.Whole
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Whole
            val spec = value.substring("bytes=".length).trim()
            if (spec.contains(',')) return RangeSpec.Whole
            if (!spec.contains('-')) return RangeSpec.Unsatisfiable
            // A zero-length resource can satisfy no range at all, not even `bytes=0-`.
            if (length <= 0L) return RangeSpec.Unsatisfiable
            return RangeSpec.Unsatisfiable
        }

        private fun extractSingleRangeSpec(header: String?): RangeParts? {
            if (header == null) return null
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return null
            val spec = value.substring("bytes=".length).trim()
            if (spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            return RangeParts(
                fromText = spec.substring(0, dash).trim(),
                toText = spec.substring(dash + 1).trim(),
            )
        }

        private data class RangeParts(val fromText: String, val toText: String)

        private fun resolveRange(parts: RangeParts, length: Long): RangeSpec {
            // A zero-length resource can satisfy no range at all, not even `bytes=0-`.
            if (length <= 0L) return RangeSpec.Unsatisfiable
            if (parts.fromText.isEmpty()) return resolveSuffixRange(parts.toText, length)
            return resolveBoundedRange(parts, length)
        }

        private fun resolveSuffixRange(toText: String, length: Long): RangeSpec {
            // A suffix range: the last N bytes.
            val suffix = toText.toLongOrNull() ?: return RangeSpec.Unsatisfiable
            if (suffix <= 0L) return RangeSpec.Unsatisfiable
            val first = maxOf(0L, length - suffix)
            return RangeSpec.Satisfiable(first, length - 1)
        }

        private fun resolveBoundedRange(parts: RangeParts, length: Long): RangeSpec {
            val first = parts.fromText.toLongOrNull() ?: return RangeSpec.Unsatisfiable
            if (first < 0L || first >= length) return RangeSpec.Unsatisfiable
            val last = resolveLast(parts.toText, first, length) ?: return RangeSpec.Unsatisfiable
            return RangeSpec.Satisfiable(first, last)
        }

        private fun resolveLast(toText: String, first: Long, length: Long): Long? {
            if (toText.isEmpty()) return length - 1
            val stated = toText.toLongOrNull() ?: return null
            if (stated < first) return null
            return minOf(stated, length - 1)
        }

        private fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val out = java.io.ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val hex = text.substring(i + 1, i + PERCENT_HEX_DIGITS).toIntOrNull(HEX_RADIX)
                    if (hex != null) {
                        out.write(hex)
                        i += PERCENT_HEX_DIGITS
                        continue
                    }
                }
                out.write(c.code)
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
