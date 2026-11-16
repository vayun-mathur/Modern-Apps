package com.vayunmathur.cast.tv.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SessionKeys
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pairing edges: attempts exhausted, and a phone that vanishes mid-pairing. */
@RunWith(RobolectricTestRunner::class)
class AuthenticateDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun channelPair(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0)
        var accepted: Socket? = null
        val thread = Thread { accepted = server.accept() }
        thread.start()
        val client = Socket("127.0.0.1", server.localPort)
        thread.join(5_000)
        server.close()
        return ControlChannel(accepted!!) to ControlChannel(client)
    }

    @Test fun pairingWrongProofsRotateCodeThenRightProofPairs() { runBlocking {
        val (tv, phone) = channelPair()
        val store = PairingStore(ctx)
        val keys = SessionKeys.of(ByteArray(32) { 12 })
        val transcript = byteArrayOf(1)
        val greeting = Hello(senderName = "Pixel", senderId = "phone-exhaust-1", paired = false)
        val result = async(Dispatchers.IO) {
            ReceiverController.authenticate(tv, store, keys, transcript, greeting)
        }
        assertEquals(true, (phone.receive()?.message as PairRequired).code)
        // Burn all three attempts with wrong proofs: 2, 1, then the code is
        // thrown away and a fresh one issued (attempts reset, codeChanged set).
        val seen = mutableListOf<PairFailed>()
        repeat(3) {
            phone.send(PairProof(ProtocolBase64.encode(byteArrayOf(0, 0, 0))))
            seen += phone.receive()?.message as PairFailed
        }
        assertEquals(listOf(2, 1, 3), seen.map { it.attemptsLeft })
        assertTrue(seen.last().codeChanged)
        // The right proof for the code now on screen still pairs.
        val pairing = ReceiverController.state.value.phase as ReceiverPhase.Pairing
        phone.send(PairProof(ProtocolBase64.encode(keys.pairProof(pairing.code, transcript))))
        assertTrue(phone.receive()?.message is com.vayunmathur.cast.protocol.PairOk)
        assertTrue(result.await())
        tv.close()
        phone.close()
    } }

    @Test fun phoneVanishingMidPairingEndsAuthentication() { runBlocking {
        val (tv, phone) = channelPair()
        val store = PairingStore(ctx)
        val keys = SessionKeys.of(ByteArray(32) { 13 })
        val greeting = Hello(senderName = "Pixel", senderId = "phone-vanish-1", paired = false)
        val result = async(Dispatchers.IO) {
            ReceiverController.authenticate(tv, store, keys, byteArrayOf(2), greeting)
        }
        assertEquals(true, (phone.receive()?.message as PairRequired).code)
        phone.close()
        assertFalse(result.await())
        tv.close()
    } }

    @Test fun rememberedKeyWithWrongProofAndVanish() { runBlocking {
        val (tv, phone) = channelPair()
        val store = PairingStore(ctx)
        store.remember("phone-known-9", ByteArray(32) { 6 })
        val keys = SessionKeys.of(ByteArray(32) { 14 })
        val greeting = Hello(senderName = "Pixel", senderId = "phone-known-9", paired = true)
        val result = async(Dispatchers.IO) {
            ReceiverController.authenticate(tv, store, keys, byteArrayOf(3), greeting)
        }
        assertEquals(false, (phone.receive()?.message as PairRequired).code)
        // Wrong device proof falls through to a code; then the phone leaves.
        phone.send(PairProof(ProtocolBase64.encode(byteArrayOf(1))))
        assertEquals(true, (phone.receive()?.message as PairRequired).code)
        phone.close()
        assertFalse(result.await())
        tv.close()
    } }
}
