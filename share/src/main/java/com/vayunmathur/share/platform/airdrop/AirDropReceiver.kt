package com.vayunmathur.share.platform.airdrop

import com.vayunmathur.library.log.Log
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.Cpio
import com.vayunmathur.share.domain.airdrop.DvZip
import com.vayunmathur.share.domain.airdrop.Uti
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.platform.IncomingTransfer
import com.vayunmathur.share.platform.ReceivedFile
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AirDropReceiver"

/** How long an Ask waits for the user; Mosey's notification shares the 60 s of Quick Share. */
private const val ASK_TIMEOUT_MS = 60_000L

/** Mosey's free-space margin: refuse unless 2.5x the announced size is available (`cdw`). */
private const val SPACE_MARGIN = 2.5

private const val GZIP_MAGIC_0 = 0x1F
private const val GZIP_MAGIC_1 = 0x8B
private const val COPY_BUFFER = 64 * 1024

// HTTP status codes returned to AirDrop peers.
private const val STATUS_OK = 200
private const val STATUS_BAD_REQUEST = 400
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_NOT_FOUND = 404
private const val STATUS_INTERNAL_SERVER_ERROR = 500

/** One incoming AirDrop transfer, from the Ask through the Upload. */
class AirDropIncoming internal constructor(
    override val sessionHandle: Long,
    val ask: AirDropMessages.AskRequest,
    val remote: InetAddress,
) : IncomingTransfer {
    internal val decision = CompletableDeferred<Boolean>()

    override val state = MutableStateFlow(ShareState.AwaitingAccept)
    override val pendingFiles: StateFlow<List<PendingFile>> = MutableStateFlow(
        ask.files.filterNot { it.isDirectory }.map {
            PendingFile(
                it.fileName,
                it.fileSize.coerceAtLeast(0),
                Uti.mimeTypeOf(it.fileType) ?: ReceivedFileStore.GENERIC_MIME_TYPE,
            )
        },
    )
    override val receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
    override val bytesReceived = MutableStateFlow(0L)
    override val expectedTotalBytes: StateFlow<Long> = MutableStateFlow(ask.totalBytes)
    override val error = MutableStateFlow<String?>(null)
    override val displayName: String get() = ask.senderComputerName

    internal fun fail(reason: String) {
        if (state.value == ShareState.Completed || state.value == ShareState.Failed) return
        error.value = reason
        state.value = ShareState.Failed
        decision.complete(false)
    }
}

/**
 * The receiving half of AirDrop: answers `/Discover`, holds `/Ask` until the user decides,
 * and unpacks `/Upload` into [ReceivedFileStore].
 *
 * Transfers surface through [incoming] as [IncomingTransfer]s, so the same notifications that
 * carry Quick Share requests carry these.
 */
class AirDropReceiver(
    private val store: ReceivedFileStore,
    private val localName: () -> String,
    private val freeBytes: () -> Long,
    private val onTransfer: (remote: InetAddress, started: Boolean) -> Unit,
) {
    private val nextHandle = AtomicLong(-1)
    private val sessions = mutableMapOf<Long, AirDropIncoming>()
    private val lock = Any()

    private val _incoming = MutableStateFlow<List<AirDropIncoming>>(emptyList())
    val incoming: StateFlow<List<AirDropIncoming>> = _incoming.asStateFlow()

    fun sessionFor(handle: Long): AirDropIncoming? = synchronized(lock) { sessions[handle] }

    fun accept(handle: Long, accept: Boolean): Int {
        val session = sessionFor(handle) ?: return -1
        if (!accept) session.fail("declined")
        session.decision.complete(accept)
        return 0
    }

    fun cancel(handle: Long) {
        sessionFor(handle)?.fail("cancelled")
    }

    fun retire(handle: Long) {
        synchronized(lock) {
            sessions.remove(handle)
            _incoming.value = sessions.values.toList()
        }
    }

    val hasActiveTransfers: Boolean
        get() = synchronized(lock) { sessions.values.any { it.state.value == ShareState.Transferring } }

    suspend fun handle(request: AirDropHttp.Request, remote: InetAddress): AirDropHttp.Response =
        when (request.path) {
            "/Discover" -> discover()
            "/Ask" -> ask(request, remote)
            "/Upload" -> upload(request, remote)
            else -> AirDropHttp.Response(STATUS_NOT_FOUND)
        }

    private fun discover(): AirDropHttp.Response {
        val body = AirDropMessages.DiscoverResponse(
            receiverComputerName = localName(),
            manufacturer = AirDropMessages.MANUFACTURER_GOOGLE,
            mediaCapabilities = AirDropMessages.DEFAULT_MEDIA_CAPABILITIES,
        ).toPlist()
        return plistResponse(body)
    }

    // Broad catch is deliberate: a malformed plist from a peer is a 401, not a crash.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ask(request: AirDropHttp.Request, remote: InetAddress): AirDropHttp.Response {
        val ask = try {
            AirDropMessages.AskRequest.fromPlist(BinaryPlist.decodeDict(request.readBody()))
        } catch (e: Exception) {
            Log.status(TAG, "unreadable Ask from ${remote.hostAddress}", e)
            null
        } ?: return AirDropHttp.Response(STATUS_UNAUTHORIZED)
        if (ask.items.isNotEmpty() && ask.files.isEmpty()) {
            // Links arrive as Items with no archive; nowhere to stage them yet.
            Log.status(TAG, "declining link-only Ask from ${ask.senderComputerName}")
            return AirDropHttp.Response(STATUS_UNAUTHORIZED)
        }
        if (freeBytes() < ask.totalBytes * SPACE_MARGIN) {
            Log.status(TAG, "not enough space for ${ask.totalBytes} bytes from ${ask.senderComputerName}")
            return AirDropHttp.Response(STATUS_UNAUTHORIZED)
        }
        val session = AirDropIncoming(nextHandle.getAndDecrement(), ask, remote)
        synchronized(lock) {
            if (sessions.values.any { it.state.value == ShareState.Transferring }) {
                return AirDropHttp.Response(STATUS_UNAUTHORIZED)
            }
            sessions[session.sessionHandle] = session
            _incoming.value = sessions.values.toList()
        }
        Log.status(TAG, "Ask from ${ask.senderComputerName} (${ask.senderModelName}): ${ask.files.size} file(s)")
        val accepted = withTimeoutOrNull(ASK_TIMEOUT_MS) { session.decision.await() } ?: false
        if (!accepted) {
            session.fail("timed out")
            return AirDropHttp.Response(STATUS_UNAUTHORIZED)
        }
        session.state.value = ShareState.Transferring
        return plistResponse(
            mapOf(
                "ReceiverModelName" to android.os.Build.MODEL,
                "ReceiverComputerName" to localName(),
            ),
        )
    }

    // Broad catch is deliberate: a bad archive fails this transfer and nothing else.
    @Suppress("TooGenericExceptionCaught")
    private fun upload(request: AirDropHttp.Request, remote: InetAddress): AirDropHttp.Response {
        val transferId = request.headers["transferid"]
        val session = synchronized(lock) {
            sessions.values.firstOrNull {
                it.state.value == ShareState.Transferring &&
                    (if (transferId != null) it.ask.transferId == transferId else it.remote == remote)
            }
        } ?: run {
            Log.status(TAG, "Upload from ${remote.hostAddress} matches no accepted Ask")
            return AirDropHttp.Response(STATUS_BAD_REQUEST)
        }
        onTransfer(remote, true)
        return try {
            val written = extract(request.headers["content-type"], request.body) { session.bytesReceived.value += it }
            session.receivedFiles.value = written.map {
                ReceivedFile(it.name, it.length(), ReceivedFileStore.mimeTypeOf(it), store.contentUri(it))
            }
            session.state.value = ShareState.Completed
            Log.status(TAG, "received ${written.size} file(s) from ${session.displayName}")
            AirDropHttp.Response(STATUS_OK)
        } catch (e: Exception) {
            Log.status(TAG, "Upload from ${session.displayName} failed", e)
            session.fail(e.message ?: "upload failed")
            AirDropHttp.Response(STATUS_INTERNAL_SERVER_ERROR)
        } finally {
            onTransfer(remote, false)
        }
    }

    /** Unpacks the body by its declared type, sniffing gzip, which Apple wraps cpio in. */
    private fun extract(contentType: String?, body: InputStream, onBytes: (Long) -> Unit): List<File> {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        val buffered = BufferedInputStream(body)
        return when (type) {
            AirDropMessages.CONTENT_TYPE_DVZIP -> extractCpio(DvZip.Reader(buffered), onBytes)
            AirDropMessages.CONTENT_TYPE_ZIP -> extractZip(ZipInputStream(buffered), onBytes)
            else -> extractCpio(if (isGzip(buffered)) GZIPInputStream(buffered) else buffered, onBytes)
        }
    }

    private fun extractCpio(input: InputStream, onBytes: (Long) -> Unit): List<File> {
        val reader = Cpio.Reader(input)
        val written = mutableListOf<File>()
        while (true) {
            val (entry, content) = reader.next() ?: break
            if (entry.isFile) {
                AirDropMessages.safeRelativePath(entry.name)?.let { name ->
                    written += writeEntry(name, content, onBytes)
                }
            }
        }
        return written
    }

    private fun extractZip(zip: ZipInputStream, onBytes: (Long) -> Unit): List<File> {
        val written = mutableListOf<File>()
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                AirDropMessages.safeRelativePath(entry.name)?.let { name ->
                    written += writeEntry(name, zip, onBytes)
                }
            }
        }
        return written
    }

    private fun writeEntry(relativePath: String, content: InputStream, onBytes: (Long) -> Unit): File {
        val file = store.newFile(relativePath)
        try {
            file.outputStream().use { out ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = content.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
        } catch (e: java.io.IOException) {
            file.delete()
            throw e
        }
        return file
    }

    private fun isGzip(input: BufferedInputStream): Boolean {
        input.mark(2)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        return b0 == GZIP_MAGIC_0 && b1 == GZIP_MAGIC_1
    }

    private fun plistResponse(body: Map<String, Any?>) = AirDropHttp.Response(
        STATUS_OK,
        mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_PLIST),
        BinaryPlist.encode(body),
    )
}
