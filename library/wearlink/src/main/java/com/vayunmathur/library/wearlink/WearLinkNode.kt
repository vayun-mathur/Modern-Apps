package com.vayunmathur.library.wearlink

import android.content.Context
import com.vayunmathur.library.log.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Callbacks for one WearLink peer session. All run off the main thread. */
interface WearLinkListener {
    /** Fire-and-forget message (or acked message) from the peer. */
    fun onMessage(path: String, body: ByteArray) {}

    /** Request message; return a response body, or null for no reply. */
    fun onRequest(path: String, body: ByteArray): ByteArray? = null

    /** A complete, hash-verified data item arrived for [path]. */
    fun onData(path: String, body: ByteArray) {}

    /**
     * A stream is opening for [path]. Return the sink to write it into, or
     * null to refuse. [StreamSink.resumeOffset]/[StreamSink.prefix] let a
     * retained partial resume instead of restarting.
     */
    fun onStreamOpen(path: String, header: StreamHeader): StreamSink? = null
}

/** Receiving end of one incoming stream. */
interface StreamSink {
    val resumeOffset: Long get() = 0L

    /** Stream of bytes already held for [resumeOffset] (fed into the hash). */
    fun prefixStream(): java.io.InputStream? = null

    fun write(bytes: ByteArray, offset: Long)

    /** Stream ended; [verified] is the library's size+SHA-256 verdict. */
    fun close(verified: Boolean)
}

/** Random-access byte source for outgoing streams. */
interface StreamSource {
    val size: Long
    val sha256: String
    fun readAt(offset: Long, length: Int): ByteArray
}

class FileStreamSource(private val file: File, override val sha256: String) : StreamSource {
    override val size: Long get() = file.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
    }
}

/**
 * One peer link for one app. Owns accept + dial loops (either side may
 * initiate), the handshake, framing, data-item reassembly, stream transfers
 * and reconnect/backoff. Listeners become app-lifecycle-bound: unlike the
 * old GMS WearableListenerService, nothing wakes a dead process when a
 * message arrives — sync resumes on next start() and state documents are
 * republished then. That is the honest cost of dropping Play services.
 */
class WearLinkNode(
    context: Context,
    private val appId: String,
    private val listener: WearLinkListener,
    private val transport: WearLinkTransport = AndroidBluetoothTransport(context.applicationContext),
    private val certHashHex: String? = SigningTrust.localCertHashHex(context.applicationContext),
    private val packageName: String = context.applicationContext.packageName,
    parentScope: CoroutineScope? = null,
) {
    companion object {
        private const val TAG = "WearLink"
        private const val STREAM_ACK_WAIT_MS = 5_000L

        /** Runtime permissions the hosting activity must grant before start. */
        val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instanceId = UUID.randomUUID().toString()
    private val ids = IdGenerator()
    private val reassembler = DataReassembler()
    private val seen = SeenMessageIds()
    private val responses = RequestTracker<CompletableDeferred<ByteArray?>>()
    private val acks = RequestTracker<CompletableDeferred<Boolean>>()
    private val streamAcks = RequestTracker<CompletableDeferred<Long>>()

    private val _state = MutableStateFlow<WearLinkState>(WearLinkState.Stopped)
    val state: StateFlow<WearLinkState> = _state.asStateFlow()
    val isConnected: Boolean get() = activeSession != null

    @Volatile private var activeSession: Session? = null
    @Volatile private var running = false
    private var acceptJob: Job? = null
    private var connectJob: Job? = null
    private var lastGeneration = 0L
    private val receiving = mutableMapOf<Long, ReceivingStream>()

    private inner class Session(
        val socket: WearLinkSocket,
        val initiatorInstance: String,
    ) {
        private val writeLock = Any()

        @Volatile var open = true

        fun send(frame: WearLinkFrame) {
            synchronized(writeLock) {
                if (!open) throw java.io.IOException("session closed")
                FrameCodec.encode(frame, socket.output)
            }
        }

        fun close() {
            open = false
            runCatching { socket.close() }
        }
    }

    private class ReceivingStream(val sink: StreamSink, val receiver: StreamReceiver)

    fun start() {
        if (running) return
        if (transport is AndroidBluetoothTransport && !transport.hasConnectPermission()) {
            _state.value = WearLinkState.PermissionMissing
            Log.status(TAG, "BLUETOOTH_CONNECT not granted")
            return
        }
        if (!transport.isAvailable()) {
            _state.value = WearLinkState.BluetoothOff
            return
        }
        if (certHashHex == null) {
            _state.value = WearLinkState.Failed("signing certificate unavailable")
            return
        }
        running = true
        acceptJob = scope.launch { acceptLoop() }
        connectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
        acceptJob?.cancel(); connectJob?.cancel()
        activeSession?.close(); activeSession = null
        failPending()
        _state.value = WearLinkState.Stopped
    }

    // -- Sending -------------------------------------------------------------

    /** Fire-and-forget, or acked when [requireAck]. False when not connected. */
    suspend fun sendMessage(path: String, body: ByteArray, requireAck: Boolean = false): Boolean {
        val session = activeSession ?: return false
        if (body.size > WearLinkProtocol.MAX_MESSAGE_BYTES) return false
        val id = ids.next()
        if (requireAck) {
            val deferred = CompletableDeferred<Boolean>()
            acks.register(id, deferred)
            return try {
                session.send(WearLinkFrame(FrameType.MESSAGE, id, path, body))
                withTimeoutOrNull(10_000) { deferred.await() } ?: false
            } catch (expected: Exception) {
                Log.status(TAG, "send $path failed: ${expected.message}"); false
            } finally {
                acks.remove(id)
            }
        }
        return runCatching {
            session.send(WearLinkFrame(FrameType.MESSAGE, id, path, body)); true
        }.getOrDefault(false)
    }

    /** Correlated request/response over MESSAGE/RESPONSE frames. */
    suspend fun request(path: String, body: ByteArray, timeoutMillis: Long = 10_000): ByteArray? {
        val session = activeSession ?: return null
        val id = ids.next()
        val deferred = CompletableDeferred<ByteArray?>()
        responses.register(id, deferred)
        return try {
            session.send(WearLinkFrame(FrameType.MESSAGE, id, path, body))
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } catch (expected: Exception) {
            Log.status(TAG, "request $path failed: ${expected.message}"); null
        } finally {
            responses.remove(id)
        }
    }

    /** Publish a path-keyed state document (chunked, generation-ordered). */
    suspend fun putData(path: String, body: ByteArray): Boolean {
        val session = activeSession ?: return false
        if (body.size > WearLinkProtocol.MAX_DATA_ITEM_BYTES) return false
        val generation = synchronized(this) {
            lastGeneration = maxOf(lastGeneration + 1, System.currentTimeMillis())
            lastGeneration
        }
        return runCatching {
            for (frame in DataItemCodec.chunk(path, generation, body)) session.send(frame)
            true
        }.getOrDefault(false)
    }

    /**
     * Stream [source] to [path]. Sends STREAM_OPEN, honours the receiver's
     * STREAM_ACK resume offset, then chunks + STREAM_CLOSE. The preceding
     * per-app offer message (size/hash contract) stays the app's job, as it
     * was over the old channel API.
     */
    suspend fun sendStream(path: String, source: StreamSource): Boolean {
        val session = activeSession ?: return false
        if (source.size > WearLinkProtocol.MAX_STREAM_BYTES) return false
        val transferId = ids.next()
        val header = StreamHeader(source.size, source.sha256)
        val deferred = CompletableDeferred<Long>()
        streamAcks.register(transferId, deferred)
        return try {
            session.send(WearLinkFrame(FrameType.STREAM_OPEN, transferId, path, header.encodeBody()))
            val resume = withTimeoutOrNull(STREAM_ACK_WAIT_MS) { deferred.await() } ?: 0L
            val sender = StreamSender(header, source.size)
            sender.seekTo(resume.coerceIn(0, source.size))
            while (true) {
                val chunk = sender.nextChunk(source::readAt) ?: break
                val offset = sender.nextOffset - chunk.size
                session.send(WearLinkFrame(FrameType.STREAM_CHUNK, transferId, path, chunk))
                // Offset is implicit (sequential delivery), kept in sync by sender state.
                check(offset >= 0)
            }
            session.send(WearLinkFrame(FrameType.STREAM_CLOSE, transferId, path, ByteArray(0)))
            true
        } catch (expected: Exception) {
            Log.status(TAG, "stream $path failed: ${expected.message}"); false
        } finally {
            streamAcks.remove(transferId)
        }
    }

    // -- Connection loops ----------------------------------------------------

    private suspend fun acceptLoop() {
        while (running) {
            try {
                val server = transport.listen(WearLinkProtocol.SERVICE_UUID, WearLinkProtocol.SERVICE_NAME)
                try {
                    while (running) {
                        val socket = server.accept()
                        scope.launch { handshakeAndAdopt(socket, isInitiator = false) }
                    }
                } finally {
                    runCatching { server.close() }
                }
            } catch (expected: Exception) {
                if (running) delay(1_000)
            }
        }
    }

    private suspend fun connectLoop() {
        val backoff = WearLinkBackoff()
        while (running) {
            if (activeSession != null) {
                backoff.reset()
                delay(2_000)
                continue
            }
            val peers = transport.bondedPeers()
            if (peers.isEmpty()) {
                _state.value = WearLinkState.NoBondedPeer
                delay(backoff.nextDelayMillis())
                continue
            }
            var connected = false
            for (peer in peers) {
                if (activeSession != null) break
                _state.value = WearLinkState.Connecting(peer.label)
                runCatching {
                    val socket = transport.connect(peer, WearLinkProtocol.SERVICE_UUID)
                    connected = handshakeAndAdopt(socket, isInitiator = true)
                }.onFailure { Log.status(TAG, "connect ${peer.label} failed: ${it.message}") }
                if (connected) break
            }
            if (!connected && activeSession == null) {
                _state.value = WearLinkState.Failed("no bonded peer runs $appId")
                delay(backoff.nextDelayMillis())
            }
        }
    }

    /** Run the handshake on [socket]; adopt if it wins. Returns adoption. */
    private fun handshakeAndAdopt(socket: WearLinkSocket, isInitiator: Boolean): Boolean {
        val cert = certHashHex ?: run { socket.close(); return false }
        return try {
            val session: Session
            if (isInitiator) {
                val nonce = WearLinkHandshake.newNonce()
                val hello = HelloMessage(
                    WearLinkProtocol.PROTOCOL_VERSION, appId, packageName, instanceId,
                    nonce, WearLinkHandshake.token(cert, nonce, appId),
                )
                FrameCodec.encode(WearLinkFrame(FrameType.HELLO, 0, "", hello.encode()), socket.output)
                val ackFrame = FrameCodec.decode(socket.input)
                    ?: throw java.io.IOException("eof in handshake")
                val ack = HelloMessage.decode(ackFrame.body)
                    ?: throw java.io.IOException("bad hello ack")
                val combined = nonce + ack.nonce
                when (val result = HandshakeVerifier.verify(ack, appId, cert, combined)) {
                    is HandshakeVerifier.Result.Rejected ->
                        throw java.io.IOException("handshake rejected: ${result.reason}")
                    is HandshakeVerifier.Result.Accepted -> Unit
                }
                session = Session(socket, instanceId)
            } else {
                val helloFrame = FrameCodec.decode(socket.input)
                    ?: throw java.io.IOException("eof in handshake")
                val hello = HelloMessage.decode(helloFrame.body)
                    ?: throw java.io.IOException("bad hello")
                when (val result = HandshakeVerifier.verify(hello, appId, cert, hello.nonce)) {
                    is HandshakeVerifier.Result.Rejected ->
                        throw java.io.IOException("handshake rejected: ${result.reason}")
                    is HandshakeVerifier.Result.Accepted -> Unit
                }
                val ackNonce = WearLinkHandshake.newNonce()
                val ack = HelloMessage(
                    WearLinkProtocol.PROTOCOL_VERSION, appId, packageName, instanceId,
                    ackNonce,
                    WearLinkHandshake.token(cert, hello.nonce + ackNonce, appId),
                )
                FrameCodec.encode(
                    WearLinkFrame(FrameType.HELLO_ACK, 0, "", ack.encode()), socket.output,
                )
                session = Session(socket, hello.instanceId)
            }
            adopt(session, socket.peerLabel)
        } catch (expected: Exception) {
            Log.status(TAG, "handshake failed: ${expected.message}")
            runCatching { socket.close() }
            false
        }
    }

    @Synchronized
    private fun adopt(session: Session, peerLabel: String): Boolean {
        val existing = activeSession
        if (existing != null && existing.open) {
            val keepCandidate = HandshakeVerifier.keepInitiatedBySmallerInstance(
                existing.initiatorInstance, session.initiatorInstance,
            )
            if (!keepCandidate) {
                session.close()
                return false
            }
            existing.close()
        }
        activeSession = session
        _state.value = WearLinkState.Connected(peerLabel)
        scope.launch { readLoop(session, peerLabel) }
        return true
    }

    private fun readLoop(session: Session, peerLabel: String) {
        try {
            while (running && session.open) {
                val frame = FrameCodec.decode(session.socket.input) ?: break
                dispatch(session, frame)
            }
        } catch (expected: Exception) {
            Log.status(TAG, "session ended: ${expected.message}")
        } finally {
            session.close()
            synchronized(this) {
                if (activeSession === session) {
                    activeSession = null
                    if (running) _state.value = WearLinkState.Failed("peer disconnected")
                }
            }
            failPending()
            synchronized(receiving) {
                receiving.values.forEach { runCatching { it.sink.close(false) } }
                receiving.clear()
            }
        }
    }

    private fun failPending() {
        responses.failAll().forEach { it.complete(null) }
        acks.failAll().forEach { it.complete(false) }
        streamAcks.failAll().forEach { it.complete(0L) }
    }

    private fun dispatch(session: Session, frame: WearLinkFrame) {
        when (frame.type) {
            FrameType.PING -> runCatching {
                session.send(WearLinkFrame(FrameType.PONG, frame.id))
            }
            FrameType.MESSAGE -> {
                session.send(WearLinkFrame(FrameType.MESSAGE_ACK, frame.id))
                if (!seen.markIfNew(frame.id)) return
                val response = listener.onRequest(frame.path, frame.body)
                if (response != null) {
                    runCatching {
                        session.send(WearLinkFrame(FrameType.RESPONSE, frame.id, frame.path, response))
                    }
                } else {
                    listener.onMessage(frame.path, frame.body)
                }
            }
            FrameType.RESPONSE -> responses.complete(frame.id)?.complete(frame.body)
            FrameType.MESSAGE_ACK -> acks.complete(frame.id)?.complete(true)
            FrameType.DATA -> reassembler.onFrame(frame)?.let { listener.onData(frame.path, it) }
            FrameType.STREAM_OPEN -> onStreamOpen(session, frame)
            FrameType.STREAM_CHUNK -> onStreamChunk(frame)
            FrameType.STREAM_CLOSE -> onStreamClose(frame)
            FrameType.STREAM_ACK -> decodeOffsetBody(frame.body)?.let {
                streamAcks.complete(frame.id)?.complete(it)
            }
            else -> Unit
        }
    }

    private fun onStreamOpen(session: Session, frame: WearLinkFrame) {
        val header = StreamHeader.decodeBody(frame.body) ?: return
        if (header.totalSize > WearLinkProtocol.MAX_STREAM_BYTES) return
        val sink = listener.onStreamOpen(frame.path, header) ?: run {
            runCatching {
                session.send(
                    WearLinkFrame(
                        FrameType.STREAM_ACK, frame.id, frame.path, encodeOffsetBody(0),
                    ),
                )
            }
            return
        }
        val receiver = StreamReceiver(header, sink.resumeOffset, sink.prefixStream())
        synchronized(receiving) { receiving[frame.id] = ReceivingStream(sink, receiver) }
        runCatching {
            session.send(
                WearLinkFrame(
                    FrameType.STREAM_ACK, frame.id, frame.path,
                    encodeOffsetBody(receiver.ackOffset),
                ),
            )
        }
    }

    private fun onStreamChunk(frame: WearLinkFrame) {
        val context = synchronized(receiving) { receiving[frame.id] } ?: return
        val offset = context.receiver.received
        if (context.receiver.onChunk(offset, frame.body)) {
            runCatching { context.sink.write(frame.body, offset) }
        }
    }

    private fun onStreamClose(frame: WearLinkFrame) {
        val context = synchronized(receiving) { receiving.remove(frame.id) } ?: return
        val verified = context.receiver.verifyComplete()
        runCatching { context.sink.close(verified) }
    }
}
