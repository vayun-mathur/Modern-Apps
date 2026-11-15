package com.vayunmathur.share.network.airdrop

import com.vayunmathur.share.domain.airdrop.AirDropMessages
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val CONNECT_TIMEOUT_MS = 5_000

/**
 * One HTTPS connection to an AirDrop receiver, reused for Discover → Ask → Upload.
 *
 * [socketFactory] comes from the selected link, so traffic is pinned to `mosey0` when the
 * peer was found there rather than leaking out over Wi-Fi.
 */
class AirDropHttpClient(
    private val address: InetAddress,
    private val port: Int,
    private val socketFactory: SocketFactory,
    private val readTimeoutMs: Int,
) : AutoCloseable {

    private val tls: SSLSocketFactory = AirDropTls.clientContext().socketFactory
    private var socket: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    /** `Host` exactly as Mosey writes it, with IPv6 bracketed (`cce.z`). */
    private val hostHeader: String
        get() {
            val host = (address as? Inet6Address)?.let {
                "[${it.hostAddress?.substringBefore('%')}]"
            } ?: address.hostAddress
            return "$host:$port"
        }

    private fun connection(): Pair<BufferedInputStream, BufferedOutputStream> {
        val existing = socket
        if (existing != null && !existing.isClosed) return input!! to output!!
        val raw: Socket = socketFactory.createSocket()
        raw.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        val ssl = tls.createSocket(raw, address.hostAddress, port, true) as SSLSocket
        ssl.enabledProtocols = AirDropTls.PROTOCOLS.filter { it in ssl.supportedProtocols }.toTypedArray()
        ssl.soTimeout = readTimeoutMs
        ssl.startHandshake()
        socket = ssl
        input = BufferedInputStream(ssl.inputStream)
        output = BufferedOutputStream(ssl.outputStream)
        return input!! to output!!
    }

    fun postPlist(path: String, body: ByteArray, keepAlive: Boolean = true): AirDropHttp.Response {
        val (inp, out) = connection()
        AirDropHttp.writeRequestHead(
            out,
            "POST",
            path,
            listOf(
                "Host" to hostHeader,
                "Content-Type" to AirDropMessages.CONTENT_TYPE_PLIST,
                "Connection" to if (keepAlive) "keep-alive" else "close",
                "Accept" to "*/*",
                "User-Agent" to AirDropMessages.USER_AGENT,
                "Accept-Language" to "en-us",
                "Accept-Encoding" to "gzip, deflate",
                "Content-Length" to body.size.toString(),
            ),
        )
        out.write(body)
        out.flush()
        val response = AirDropHttp.readResponse(inp)
        if (!keepAlive) close()
        return response
    }

    /** Streams an `/Upload` body through [writeBody] using chunked transfer encoding. */
    fun upload(
        contentType: String,
        totalBytes: Long,
        transferId: String?,
        writeBody: (OutputStream) -> Unit,
    ): AirDropHttp.Response {
        val (inp, out) = connection()
        val headers = buildList {
            add("Host" to hostHeader)
            add("Content-Type" to contentType)
            add("Connection" to "keep-alive")
            add("User-Agent" to AirDropMessages.USER_AGENT)
            add("TotalBytes" to totalBytes.toString())
            add("Transfer-Encoding" to "chunked")
            add("Accept-Encoding" to "identity")
            transferId?.let { add("TransferID" to it) }
        }
        AirDropHttp.writeRequestHead(out, "POST", "/Upload", headers)
        val chunked = AirDropHttp.ChunkedOutputStream(out)
        writeBody(chunked)
        chunked.finish()
        return AirDropHttp.readResponse(inp)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
