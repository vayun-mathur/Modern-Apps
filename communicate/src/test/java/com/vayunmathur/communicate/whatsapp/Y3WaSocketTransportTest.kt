package com.vayunmathur.communicate.whatsapp

import android.util.Base64
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppHandshakeProto
import com.vayunmathur.communicate.data.whatsapp.buildFramedMessage
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y3: WhatsAppSocket transport internals, driven reflectively because the
 * real endpoint is a raw-TCP Noise server: ClientHello framing, ServerHello
 * processing up to the (unforgeable) WhatsApp root-cert check, frame
 * reassembly in ingestBytes, and post-handshake Noise encrypt/decrypt
 * round-trips through send()/messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3WaSocketTransportTest {

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
        identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
        registrationId = 14, signedPreKeyId = 5,
        signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
        signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "12345@lid",
    )

    private fun field(obj: Any, name: String): Any? {
        val f = obj.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(obj)
    }
    private fun setField(obj: Any, name: String, value: Any?) {
        val f = obj.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(obj, value)
    }
    private fun socketWithOutput(): Pair<WhatsAppSocket, ByteArrayOutputStream> {
        val socket = WhatsAppSocket(auth, host = "127.0.0.1", port = 9)
        val out = ByteArrayOutputStream()
        setField(socket, "output", out)
        return socket to out
    }


    private fun startReading(socket: WhatsAppSocket, bytes: ByteArray) {
        setField(socket, "input", java.io.ByteArrayInputStream(bytes))
        val m = WhatsAppSocket::class.java.getDeclaredMethod("startReadLoop"); m.isAccessible = true
        m.invoke(socket)
    }

    @Test fun clientHelloIsFramedAndHandshakeStateSeeded() {
        val (socket, out) = socketWithOutput()
        val m = WhatsAppSocket::class.java.getDeclaredMethod("startNoiseHandshake"); m.isAccessible = true
        m.invoke(socket)
        val framed = out.toByteArray()
        assertTrue(framed.size > 4)
        // WA_CONN_HEADER prefix, then 3-byte length, then a ClientHello with a 32-byte ephemeral.
        assertEquals('W'.code.toByte(), framed[0]); assertEquals('A'.code.toByte(), framed[1])
        val payload = framed.copyOfRange(4 + 3, framed.size)
        val hello = WhatsAppHandshakeProto.HandshakeMessage.parseFrom(payload)
        assertEquals(32, hello.clientHello.ephemeral.size())
        assertTrue(field(socket, "noiseHandshake") != null)
        assertEquals(32, (field(socket, "ephemeralPrivateKey") as ByteArray).size)
    }

    @Test fun serverHelloWithForgedCertFailsVerificationAndCloses() = runBlocking {
        val (socket, out) = socketWithOutput()
        val start = WhatsAppSocket::class.java.getDeclaredMethod("startNoiseHandshake"); start.isAccessible = true
        start.invoke(socket)
        val clientHello = WhatsAppHandshakeProto.HandshakeMessage.parseFrom(
            out.toByteArray().copyOfRange(4 + 3, out.size()))
        val clientEph = clientHello.clientHello.ephemeral.toByteArray()

        // Mirror the server side of Noise_XX with our own static key + a forged cert payload.
        val server = WhatsAppProtocol.NoiseHandshake()
        server.start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
        server.authenticate(clientEph)
        val (serverEphPriv, serverEphPub) = WhatsAppProtocol.generateX25519KeyPair()
        server.authenticate(serverEphPub)
        server.mixSharedSecretIntoKey(serverEphPriv, clientEph)
        val (_, serverStaticPub) = WhatsAppProtocol.generateX25519KeyPair()
        val staticCt = server.encrypt(serverStaticPub)
        server.mixSharedSecretIntoKey(serverEphPriv, serverStaticPub)
        val certCt = server.encrypt(byteArrayOf(1, 2, 3)) // not a WA cert chain: must fail verify

        val serverHello = WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
            .setServerHello(
                WhatsAppHandshakeProto.HandshakeMessage.ServerHello.newBuilder()
                    .setEphemeral(ByteString.copyFrom(serverEphPub))
                    .setStatic(ByteString.copyFrom(staticCt))
                    .setPayload(ByteString.copyFrom(certCt)))
            .build().toByteArray()

        val framed = WhatsAppProtocol.buildFramedMessage(serverHello, WhatsAppProtocol.WA_CONN_HEADER)
        startReading(socket, framed)
        // Cert verification fails -> closeInternal runs on the socket scope and clears the
        // handshake state; the handshake never completes.
        val deadline = System.currentTimeMillis() + 5_000
        while (field(socket, "noiseHandshake") != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(field(socket, "noiseHandshake") == null, "expected closeInternal after cert failure")
        assertFalse(field(socket, "isHandshakeComplete") as Boolean)
        socket.disconnect()
    }

    @Test fun invalidServerHelloSizesAreRejected() = runBlocking {
        val (socket, _) = socketWithOutput()
        val start = WhatsAppSocket::class.java.getDeclaredMethod("startNoiseHandshake"); start.isAccessible = true
        start.invoke(socket)
        val bad = WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
            .setServerHello(
                WhatsAppHandshakeProto.HandshakeMessage.ServerHello.newBuilder()
                    .setEphemeral(ByteString.copyFrom(ByteArray(10)))
                    .setStatic(ByteString.copyFrom(ByteArray(4)))
                    .setPayload(ByteString.copyFrom(ByteArray(4))))
            .build().toByteArray()
        startReading(socket, WhatsAppProtocol.buildFramedMessage(bad, WhatsAppProtocol.WA_CONN_HEADER))
        val deadline2 = System.currentTimeMillis() + 5_000
        while (field(socket, "noiseHandshake") != null && System.currentTimeMillis() < deadline2) {
            Thread.sleep(25)
        }
        assertFalse(field(socket, "isHandshakeComplete") as Boolean)
        socket.disconnect()
    }

    @Test fun postHandshakeFramesDecryptToMessagesAndSendEncrypts() = runBlocking {
        val (socket, out) = socketWithOutput()
        // Install a NoiseSocket with known keys, as sendClientFinish would after a real handshake.
        val noiseClass = Class.forName(
            "com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket\$NoiseSocket")
        val ctor = noiseClass.getDeclaredConstructor(SecretKeySpec::class.java, SecretKeySpec::class.java)
        ctor.isAccessible = true
        val key = SecretKeySpec(ByteArray(32) { (it * 3 + 1).toByte() }, "AES")
        val noise = ctor.newInstance(key, key)
        setField(socket, "noiseSocket", noise)
        setField(socket, "isConnected", true)
        setField(socket, "isHandshakeComplete", true)

        assertTrue(socket.send(byteArrayOf(9, 8, 7)))
        assertTrue(out.size() > 3)

        // A peer NoiseSocket with the same keys encrypts a frame we then ingest (split across reads).
        val peerNoise = ctor.newInstance(key, key)
        val encrypt = noiseClass.getDeclaredMethod("encrypt", ByteArray::class.java); encrypt.isAccessible = true
        // Advance peer counter past our send (counters are independent per direction in this harness,
        // both start at 0; our socket's read counter starts at 0, peer encrypt counter at 0 -> matches).
        val frame = encrypt.invoke(peerNoise, "hello noise".toByteArray()) as ByteArray
        val framed = WhatsAppProtocol.buildFramedMessage(frame, WhatsAppProtocol.WA_CONN_HEADER)
        val garbage = WhatsAppProtocol.buildFramedMessage(byteArrayOf(1, 2, 3, 4), null)
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async { socket.messages.first() }
        startReading(socket, framed + garbage)
        val msg = withTimeoutOrNull(5_000) { collector.await() }
        assertEquals("hello noise", msg?.decodeToString())
        socket.disconnect()
    }

    @Test fun sendFailsClosedWhenNotConnected() {
        val socket = WhatsAppSocket(auth, host = "127.0.0.1", port = 9)
        assertFalse(socket.send(byteArrayOf(1)))
        socket.disconnect()
    }
}
