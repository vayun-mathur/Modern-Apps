package com.vayunmathur.share.platform.airdrop

import android.os.Build
import com.vayunmathur.library.log.Log
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.Cpio
import com.vayunmathur.share.domain.airdrop.DvZip
import com.vayunmathur.share.network.airdrop.AirDropHttpClient
import com.vayunmathur.share.platform.airdrop.link.AirDropLink
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AirDropSender"

/** Discover is answered immediately; anything slower is a peer we cannot use. */
private const val DISCOVER_TIMEOUT_MS = 5_000

/** Ask blocks until the receiving user decides, and the upload can stall on a busy link. */
private const val TRANSFER_TIMEOUT_MS = 120_000

/** The sending half of AirDrop: `/Discover` for a name, then `/Ask` and a dvzip `/Upload`. */
class AirDropSender(private val localName: () -> String) {

    /** Contract for result. */
    sealed interface Result {
        /** Singleton holding sent state and operations. */
        data object Sent : Result
        /** Singleton holding declined state and operations. */
        data object Declined : Result
        /** Immutable value describing failed; carries reason. */
        data class Failed(val reason: String) : Result
    }

    private val senderId = AirDropMessages.randomShortId()

    /**
     * The receiver's advertised name, or null when it is unreachable, unreadable, or a Google
     * device — which Mosey skips because Quick Share already reaches it (`cbz`).
     */
    // Broad catch is deliberate: any failure to talk to a candidate means "not listed".
    @Suppress("TooGenericExceptionCaught")
    suspend fun discover(
        peer: AirDropPeer,
        link: AirDropLink,
    ): AirDropMessages.DiscoverResponse? = withContext(Dispatchers.IO) {
        try {
            AirDropHttpClient(peer.address, peer.port, link.socketFactory, DISCOVER_TIMEOUT_MS).use { client ->
                val resp = client.postPlist(
                    "/Discover",
                    BinaryPlist.encode(emptyMap<String, Any?>()),
                    keepAlive = false,
                )
                if (!resp.isSuccessful) return@withContext null
                val parsed = AirDropMessages.DiscoverResponse.fromPlist(
                    BinaryPlist.decodeDict(resp.body),
                ) ?: return@withContext null
                if (parsed.manufacturer.equals(AirDropMessages.MANUFACTURER_GOOGLE, ignoreCase = true)) {
                    Log.dev(TAG, "skipping Google device ${parsed.receiverComputerName}")
                    return@withContext null
                }
                parsed
            }
        } catch (e: Exception) {
            Log.dev(TAG, "Discover ${peer.serviceName} failed: ${e.message}")
            null
        }
    }

    // Broad catch is deliberate: a transfer failure is reported, never thrown at the UI.
    @Suppress("TooGenericExceptionCaught")
    suspend fun send(
        peer: AirDropPeer,
        link: AirDropLink,
        files: List<File>,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val transferId = UUID.randomUUID().toString()
        val total = files.sumOf { it.length() }
        val ask = AirDropMessages.AskRequest(
            senderComputerName = localName(),
            senderModelName = Build.MODEL,
            senderId = senderId,
            files = files.map { AirDropMessages.AskFile.forFile(it.name, it.length()) },
            transferId = transferId,
        )
        try {
            AirDropHttpClient(peer.address, peer.port, link.socketFactory, TRANSFER_TIMEOUT_MS).use { client ->
                val askResp = client.postPlist("/Ask", BinaryPlist.encode(ask.toPlist()))
                if (!askResp.isSuccessful) {
                    Log.status(TAG, "Ask declined by ${peer.serviceName}: ${askResp.status}")
                    return@withContext Result.Declined
                }
                link.onTransferStarted(peer.address)
                try {
                    var sent = 0L
                    val upload = client.upload(AirDropMessages.CONTENT_TYPE_DVZIP, total, transferId) { body ->
                        val dv = DvZip.Writer(body)
                        val cpio = Cpio.Writer(dv)
                        for (file in files) {
                            file.inputStream().use { raw ->
                                val counted = Counting(raw) { n ->
                                    sent += n
                                    onProgress(sent, total)
                                }
                                cpio.file("./${file.name}", file.length(), counted)
                            }
                        }
                        cpio.finish()
                        dv.close()
                    }
                    if (upload.isSuccessful) Result.Sent else Result.Failed("receiver answered ${upload.status}")
                } finally {
                    link.onTransferEnded(peer.address)
                }
            }
        } catch (e: Exception) {
            Log.status(TAG, "send to ${peer.serviceName} failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private class Counting(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
    }
}
