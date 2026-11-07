package com.vayunmathur.cast.tv.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SessionKeys
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthenticateTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun channelPair(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0)
        var accepted: Socket? = null
        val t = Thread { accepted = server.accept() }
        t.start()
        val client = Socket("127.0.0.1", server.localPort)
        t.join(5000)
        server.close()
        return ControlChannel(accepted!!) to ControlChannel(client)
    }

    @Test fun pairWithCodeWrongThenRight() { runBlocking {
        val (tv, phone) = channelPair()
        val store = PairingStore(ctx)
        val keys = SessionKeys.of(ByteArray(32){7})
        val transcript = byteArrayOf(1, 2, 3)
        val greeting = Hello(senderName = "Pixel", senderId = "phone-code-1", paired = false)
        val result = async(kotlinx.coroutines.Dispatchers.IO) { ReceiverController.authenticate(tv, store, keys, transcript, greeting) }
        val required = phone.receive()?.message as PairRequired
        assertEquals(true, required.code); assertEquals(3, required.attemptsLeft)
        // wrong proof
        phone.send(PairProof(ProtocolBase64.encode(byteArrayOf(0, 0, 0))))
        val failed = phone.receive()?.message as PairFailed
        assertEquals(2, failed.attemptsLeft); assertEquals(false, failed.codeChanged)
        // correct proof from the code now on screen
        val pairing = ReceiverController.state.value.phase as ReceiverPhase.Pairing
        assertEquals("Pixel", pairing.senderName)
        phone.send(PairProof(ProtocolBase64.encode(keys.pairProof(pairing.code, transcript))))
        val ok = phone.receive()?.message as PairOk
        assertTrue(ok.deviceKey != null)
        assertEquals(true, result.await())
        assertTrue(store.deviceKey("phone-code-1") != null)
        tv.close(); phone.close()
    } }
    @Test fun rememberedKeyAuthenticatesWithoutCode() { runBlocking {
        val (tv, phone) = channelPair()
        val store = PairingStore(ctx)
        val deviceKey = ByteArray(32) { 5 }
        store.remember("phone-known-1", deviceKey)
        val keys = SessionKeys.of(ByteArray(32){8})
        val transcript = byteArrayOf(9)
        val greeting = Hello(senderName = "Pixel", senderId = "phone-known-1", paired = true)
        val result = async(kotlinx.coroutines.Dispatchers.IO) { ReceiverController.authenticate(tv, store, keys, transcript, greeting) }
        val required = phone.receive()?.message as PairRequired
        assertEquals(false, required.code)
        phone.send(PairProof(ProtocolBase64.encode(keys.deviceProof(deviceKey, transcript))))
        assertTrue(phone.receive()?.message is PairOk)
        assertEquals(true, result.await())
        tv.close(); phone.close()
    } }
    @Test fun rememberedKeyFailureFallsThroughToCode() { runBlocking {
        val (tv, phone) = channelPair()
        val store = PairingStore(ctx)
        store.remember("phone-known-2", ByteArray(32) { 6 })
        val keys = SessionKeys.of(ByteArray(32){3})
        val transcript = byteArrayOf(4)
        val greeting = Hello(senderName = "Pixel", senderId = "phone-known-2", paired = true)
        val result = async(kotlinx.coroutines.Dispatchers.IO) { ReceiverController.authenticate(tv, store, keys, transcript, greeting) }
        assertEquals(false, (phone.receive()?.message as PairRequired).code)
        phone.send(PairProof(ProtocolBase64.encode(byteArrayOf(1)))) // bad device proof
        // falls through to code pairing
        assertEquals(true, (phone.receive()?.message as PairRequired).code)
        val pairing = ReceiverController.state.value.phase as ReceiverPhase.Pairing
        phone.send(PairProof(ProtocolBase64.encode(keys.pairProof(pairing.code, transcript))))
        assertTrue(phone.receive()?.message is PairOk)
        assertEquals(true, result.await())
        tv.close(); phone.close()
    } }
    @Test fun closedChannelFailsAuthentication() { runBlocking {
        val (tv, phone) = channelPair()
        phone.close()
        val store = PairingStore(ctx)
        val keys = SessionKeys.of(ByteArray(32){1})
        // receive on a closed peer returns null -> authenticate returns false rather than throwing
        val ok = runCatching { ReceiverController.authenticate(tv, store, keys, byteArrayOf(0), Hello(senderName = "P", senderId = "gone", paired = false)) }.getOrDefault(false)
        assertEquals(false, ok)
        tv.close()
    } }
}
