package com.vayunmathur.share.platform.airdrop

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import com.vayunmathur.share.domain.airdrop.Cpio
import com.vayunmathur.share.domain.airdrop.DvZip
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.network.airdrop.AirDropHttp
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirDropReceiverTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val remote: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun receiver(free: Long = 1_000_000_000L) =
        AirDropReceiver(ReceivedFileStore(ctx), { "TestMac" }, { free }, { _, _ -> })

    private fun request(path: String, body: ByteArray, headers: Map<String, String> = emptyMap()): AirDropHttp.Request {
        val h = AirDropHttp.Headers(headers.mapKeys { it.key.lowercase() })
        return AirDropHttp.Request("POST", path, h, ByteArrayInputStream(body))
    }

    @Test
    fun `discover returns our computer name`() = runBlocking {
        val r = receiver()
        val resp = r.handle(request("/Discover", BinaryPlist.encode(emptyMap<String, Any?>())), remote)
        assertEquals(200, resp.status)
        val parsed = AirDropMessages.DiscoverResponse.fromPlist(BinaryPlist.decodeDict(resp.body))!!
        assertEquals("TestMac", parsed.receiverComputerName)
    }

    @Test
    fun `unknown path is 404 and malformed ask is 401`() = runBlocking {
        val r = receiver()
        assertEquals(404, r.handle(request("/Nope", ByteArray(0)), remote).status)
        assertEquals(401, r.handle(request("/Ask", byteArrayOf(1, 2, 3)), remote).status)
    }

    @Test
    fun `ask declined when not enough space`() = runBlocking {
        val ask = AirDropMessages.AskRequest(
            senderComputerName = "Sender", senderModelName = "Phone", senderId = "abcd",
            files = listOf(AirDropMessages.AskFile.forFile("big.bin", 10_000_000L)),
        )
        val r = receiver(free = 100L)
        assertEquals(401, r.handle(request("/Ask", BinaryPlist.encode(ask.toPlist())), remote).status)
    }

    @Test
    fun `ask then upload dvzip completes with staged files`() = runBlocking {
        val r = receiver()
        val files = listOf(AirDropMessages.AskFile.forFile("note.txt", 11L))
        val ask = AirDropMessages.AskRequest(
            senderComputerName = "PeerPhone", senderModelName = "Phone", senderId = "wxyz",
            files = files, transferId = "tid-1",
        )
        val askJob = async { r.handle(request("/Ask", BinaryPlist.encode(ask.toPlist())), remote) }
        // Wait for session to appear, then accept
        var handle: Long? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            handle = r.incoming.value.firstOrNull()?.sessionHandle
            if (handle != null) break
            delay(20)
        }
        assertNotNull(handle)
        val incoming = r.sessionFor(handle!!)!!
        assertEquals(ShareState.AwaitingAccept, incoming.state.value)
        assertEquals("PeerPhone", incoming.displayName)
        assertEquals(1, incoming.pendingFiles.value.size)
        assertEquals("note.txt", incoming.pendingFiles.value[0].name)
        assertEquals(0, r.accept(handle, true))
        val askResp = askJob.await()
        assertEquals(200, askResp.status)
        assertEquals(ShareState.Transferring, incoming.state.value)
        assertTrue(r.hasActiveTransfers)

        // Build dvzip upload body
        val bodyOut = ByteArrayOutputStream()
        val dv = DvZip.Writer(bodyOut)
        val cpio = Cpio.Writer(dv)
        cpio.file("./note.txt", 11, ByteArrayInputStream("hello drop!".toByteArray()))
        cpio.finish(); dv.close()
        val uploadResp = r.handle(
            request("/Upload", bodyOut.toByteArray(), mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_DVZIP, "TransferID" to "tid-1")),
            remote,
        )
        assertEquals(200, uploadResp.status)
        assertEquals(ShareState.Completed, incoming.state.value)
        assertEquals(1, incoming.receivedFiles.value.size)
        assertEquals("note.txt", incoming.receivedFiles.value[0].name)
        assertFalse(r.hasActiveTransfers)
        // second upload with no session -> 400 handled by fresh receiver
        // retire + cancel semantics
        assertEquals(-1, r.accept(999999L, true))
        r.retire(handle)
        assertEquals(null, r.sessionFor(handle))
    }

    @Test
    fun `ask reject path returns 401`() = runBlocking {
        val r = receiver()
        val ask = AirDropMessages.AskRequest(
            senderComputerName = "Peer", senderModelName = "Phone", senderId = "wxyz",
            files = listOf(AirDropMessages.AskFile.forFile("a.txt", 1L)),
        )
        val job = async { r.handle(request("/Ask", BinaryPlist.encode(ask.toPlist())), remote) }
        var handle: Long? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            handle = r.incoming.value.firstOrNull()?.sessionHandle
            if (handle != null) break
            delay(20)
        }
        assertNotNull(handle)
        assertEquals(0, r.accept(handle!!, false))
        assertEquals(401, job.await().status)
        assertEquals(ShareState.Failed, r.sessionFor(handle)?.state?.value)
    }

    @Test
    fun `upload without matching ask is 400`() = runBlocking {
        val r = receiver()
        assertEquals(400, r.handle(request("/Upload", byteArrayOf(1), mapOf("Content-Type" to "application/x-dvzip")), remote).status)
    }
}
