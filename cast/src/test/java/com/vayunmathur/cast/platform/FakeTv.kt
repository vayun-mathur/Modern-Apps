package com.vayunmathur.cast.platform

import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.CodecLimits
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.ControlCodec
import com.vayunmathur.cast.protocol.ControlFraming
import com.vayunmathur.cast.protocol.ControlMessage
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.DisplayMode
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.PairingGate
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.PqcIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** In-memory key store so a fake TV can hold a real PQC identity in a JVM test. */
class MemoryKeyStore : E2eeKeyStore {
    private val map = HashMap<String, ByteArray>()
    override suspend fun getBytes(name: String): ByteArray? = map[name]
    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        if (!onlyIfAbsent || name !in map) map[name] = value
    }
}

/**
 * A scripted TV end of the control channel: real sockets, real crypto, real protocol
 * messages, so [MirrorClient] is tested against the wire rather than a mock of it.
 */
class FakeTv(
    val version: Int = com.vayunmathur.cast.protocol.PROTOCOL_VERSION,
    private val codeRequired: Boolean = true,
    private val rememberedKey: ByteArray? = null,
    private val streamPort: Int = 45_678,
    private val contentAccepts: Boolean = true,
    private val identityOverride: ControlMessage? = null,
    private val bundleOverride: String? = null,
    private val limitsOverride: DecoderLimits? = null,
    private val answerStream: Boolean = true,
) {
    val identity: PqcIdentity = runBlocking { PqcIdentity.loadOrCreate(MemoryKeyStore(), "tv") }
    val gate = PairingGate { CODE }
    val received = CopyOnWriteArrayList<ControlMessage>()

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    private val codec = ControlCodec()

    /** Messages to push at the phone once pairing completes (for awaitEnd tests). */
    var afterPair: List<ControlMessage> = emptyList()

    private val worker = thread(name = "fake-tv") { serve() }

    private fun send(message: ControlMessage): ByteArray {
        val body = codec.encode(message)
        val out = output!!
        synchronized(out) {
            out.write(ControlFraming.encode(body))
            out.flush()
        }
        return body
    }

    /** Push a message from the test thread (after pairing). */
    fun push(message: ControlMessage) {
        send(message)
    }

    private fun serve() {
        try {
            val client = server.accept()
            socket = client
            val input = DataInputStream(client.inputStream.buffered())
            val out = DataOutputStream(client.outputStream.buffered())
            output = out
            val transcript = Transcript()

            val helloBody = ControlFraming.read(input) ?: return
            val hello = codec.decode(helloBody) as? Hello ?: return
            received += hello
            transcript.add(helloBody)

            val limits = limitsOverride ?: DecoderLimits(
                videoCodecs = listOf(
                    CodecLimits(VideoCodec.Hevc, 3840, 2160, 60, 20_000_000),
                    CodecLimits(VideoCodec.Av1, 3840, 2160, 60, 20_000_000),
                ),
                audioCodecs = listOf(com.vayunmathur.cast.protocol.AudioCodec.Opus),
            )
            val identityMessage = identityOverride ?: TvIdentity(
                version = version,
                receiverName = "Fake TV",
                receiverId = "tv-fake-1",
                publicBundle = bundleOverride
                    ?: ProtocolBase64.encode(identity.publicBundle),
                limits = limits,
                displayModes = listOf(DisplayMode(1920, 1080, 60f), DisplayMode(1280, 720, 60f)),
            )
            transcript.add(send(identityMessage))
            if (identityOverride != null) {
                drain(input)
                return
            }

            val sealedBody = ControlFraming.read(input) ?: return
            val sealed = codec.decode(sealedBody) as? SealedSecret ?: return
            received += sealed
            transcript.add(sealedBody)
            val secret = SecretSealing.open(
                identity,
                ProtocolBase64.decode(sealed.sealed) ?: return,
            ) ?: return
            val keys = SessionKeys.of(secret)
            codec.useSessionKey(keys.control)
            val frozen = transcript.value()

            send(PairRequired(code = codeRequired))
            loop(input, keys, frozen)
        } catch (_: Exception) {
            // Socket closed by the test: the ordinary end of a fake TV.
        }
    }

    private fun drain(input: DataInputStream) {
        while (ControlFraming.read(input) != null) Unit
    }

    private fun loop(input: DataInputStream, keys: SessionKeys, frozen: ByteArray) {
        while (true) {
            val body = ControlFraming.read(input) ?: return
            val message = codec.decode(body) ?: return
            received += message
            when (message) {
                is com.vayunmathur.cast.protocol.PairProof -> answerProof(message, keys, frozen)
                is StreamConfig -> if (answerStream) {
                    send(StreamReady(udpPort = streamPort, audioSsrc = 111L, videoSsrc = 222L))
                }
                is ContentSession -> send(
                    ContentReady(contentAccepts, if (contentAccepts) "" else "no opus here"),
                )
                is Bye -> return
                is Ping -> send(Ping)
                else -> Unit // recorded above; nothing to answer
            }
        }
    }

    @Volatile private var needCode: Boolean = codeRequired

    private fun answerProof(
        proof: com.vayunmathur.cast.protocol.PairProof,
        keys: SessionKeys,
        frozen: ByteArray,
    ) {
        val bytes = ProtocolBase64.decode(proof.proof) ?: ByteArray(0)
        if (needCode) {
            when (val result = gate.verifyCode(keys, frozen, bytes)) {
                is com.vayunmathur.cast.protocol.PairResult.Ok -> {
                    send(PairOk(result.deviceKey?.let { ProtocolBase64.encode(it) }))
                    afterPair.forEach { send(it) }
                }
                is com.vayunmathur.cast.protocol.PairResult.Wrong ->
                    send(PairFailed(result.attemptsLeft, result.codeChanged))
            }
        } else {
            val key = rememberedKey ?: ByteArray(0)
            when (val result = gate.verifyDevice(keys, frozen, key, bytes)) {
                is com.vayunmathur.cast.protocol.PairResult.Ok -> {
                    send(PairOk(null))
                    afterPair.forEach { send(it) }
                }
                is com.vayunmathur.cast.protocol.PairResult.Wrong -> {
                    needCode = true
                    send(PairRequired(code = true))
                }
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        runCatching { server.close() }
        worker.join(2_000)
    }

    companion object {
        const val CODE = "123456"
    }
}
