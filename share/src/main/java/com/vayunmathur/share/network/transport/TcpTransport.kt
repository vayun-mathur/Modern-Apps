package com.vayunmathur.share.network.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareSession
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.IncomingTransfer
import com.vayunmathur.share.platform.ReceivedFile
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TcpTransport"
private const val SOCKET_TIMEOUT_MS = 30_000
private const val CONNECT_TIMEOUT_MS = 10_000

/**
 * How long to wait for the peer's user to accept before giving up.
 *
 * Ours, not a recovered constant: GMS's own timeouts are Phenotype-driven. Long enough for
 * someone to look at the phone and tap.
 */
internal const val ACCEPT_WAIT_MS = 60_000L

/**
 * Read timeout inside the pump loop.
 *
 * Short so the loop wakes often enough to send keep-alives and re-poll state, not because
 * the peer is expected to be this chatty.
 */
private const val PUMP_READ_TIMEOUT_MS = 2_000

/**
 * How often to emit `KEEP_ALIVE`, matching the interval we advertise in
 * `ConnectionRequestFrame.keep_alive_interval_millis`.
 *
 * Not optional: a peer that is told to expect keep-alives every 5 s tears the connection
 * down when they never arrive, which looks exactly like an unexplained disconnect a dozen
 * seconds into every transfer.
 */
private const val KEEP_ALIVE_EVERY_MS = 5_000L

/** Poll interval while waiting for the peer to accept. */
internal const val POLL_INTERVAL_MS = 100L

/** Read buffer for one pump iteration; frames are reassembled in Rust. */
private const val PUMP_BUFFER_SIZE = 8192

/** Chunk size for one `writeChunk` call while streaming a file. */
internal const val STREAM_CHUNK_SIZE = 64 * 1024

/**
 * TCP transport pump that wires raw bytes to/from the Rust [ShareSession]
 * per PROTOCOL_CONTRACT.md §3 and §6.
 *
 * Kotlin owns: TCP listen/connect, raw socket I/O.
 * Rust owns: the 4-byte big-endian length prefix, protobuf, the D2D secure channel, and
 * partial-read buffering — Kotlin does NO framing. Everything
 * [ShareSession.drainOutbound] returns is already framed (`frame.rs frame_with_length`);
 * Kotlin writes it verbatim. Inbound bytes go in verbatim via [ShareSession.feedInbound]
 * and Rust's `inbound_buf` reassembles partial frames.
 *
 * Contract loop (per §3):
 *   bytes = tcpSocket.read()          // raw read, blocking
 *   if (bytes != null) feedInbound(handle, bytes)
 *   while ((rec = drainReceived(handle)) != null) receivedStore.append(rec)
 *   while ((out = drainOutbound(handle)) != null) tcpSocket.write(out)  // raw write
 *   poll state via queryState / queryPendingFiles
 *
 * Each peer gets its own [ShareSession] (handle lifetime owns the Rust session). The
 * session's role follows the socket: the side that dialled is the initiator.
 *
 * Connections are keyed by [Connection.sessionHandle], the only identity that survives a
 * `PendingIntent`: a notification action cannot carry a [Connection], which wraps a live
 * socket and a native handle.
 */
class TcpTransport(
    localName: String,
    private val receivedStore: ReceivedFileStore,
    localEndpointInfo: ByteArray = ByteArray(0),
    localEndpointId: String = "",
) {
    private var localName: String = localName
    private var localEndpointInfo: ByteArray = localEndpointInfo
    private var localEndpointId: String = localEndpointId

    /**
     * Set the identity new sessions announce, after the user renames the device.
     *
     * Must be the same identity that is being advertised over mDNS and BLE: the peer
     * matches `CONNECTION_REQUEST` against what it discovered. Sessions already running
     * keep the identity they were created with.
     */
    fun setLocalIdentity(name: String, endpointInfo: ByteArray, endpointId: String) {
        localName = name
        localEndpointInfo = endpointInfo
        localEndpointId = endpointId
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null

    private val _listenPort = MutableStateFlow<Int?>(null)
    val listenPort: StateFlow<Int?> = _listenPort.asStateFlow()

    private val _connections = MutableStateFlow<List<Connection>>(emptyList())

    /** Every live session, inbound and outbound. */
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    /**
     * Only the sessions a peer dialled *us* on.
     *
     * An outgoing transfer used to appear here too, so sending a file to someone raised an
     * "incoming request" against ourselves.
     */
    val incomingConnections: StateFlow<List<Connection>> = _connections
        .map { conns -> conns.filter { it.incoming } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Active session pumps, keyed by native session handle. */
    private val byHandle = mutableMapOf<Long, Connection>()
    private val connectionsLock = Any()

    private fun publishConnections() {
        synchronized(connectionsLock) {
            _connections.value = byHandle.values.toList()
        }
    }

    /** The live connection for [handle], or null once its session is gone. */
    fun connectionFor(handle: Long): Connection? =
        synchronized(connectionsLock) { byHandle[handle] }

    // ------------------------------------------------------------------
    // Server (Receive)
    // ------------------------------------------------------------------

    /**
     * Open an ephemeral TCP ServerSocket and pump each incoming client through
     * a fresh [ShareSession]. Returns the bound port (for NSD advertisement).
     *
     * Broad catch is deliberate: the accept loop must survive undocumented
     * RuntimeExceptions from the socket layer, logging while active.
     */
    @Suppress("TooGenericExceptionCaught")
    fun listen(): Int {
        if (serverSocket != null) return _listenPort.value ?: 0
        val server = ServerSocket(0, 10, InetAddress.getByName("0.0.0.0"))
        serverSocket = server
        _listenPort.value = server.localPort
        Log.status(TAG, "listening on port ${server.localPort}")
        listenJob = scope.launch {
            while (isActive) {
                try {
                    val client = withContext(Dispatchers.IO) { server.accept() }
                    client.tcpNoDelay = true
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    Log.status(TAG, "accepted ${client.inetAddress.hostAddress}:${client.port}")
                    launchConnection(client, incoming = true)
                } catch (e: Exception) {
                    if (isActive) Log.status(TAG, "accept failed", e)
                    else break
                }
            }
        }
        return server.localPort
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _listenPort.value = null
        Log.debug(TAG, "stopped listening")
    }

    // ------------------------------------------------------------------
    // Client (Send)
    // ------------------------------------------------------------------

    /**
     * Connect to [host]:[port] and pump a session over the socket.
     * Returns the [Connection] for UI progress binding.
     */
    suspend fun connect(host: String, port: Int): Connection = withContext(Dispatchers.IO) {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.soTimeout = SOCKET_TIMEOUT_MS
        sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        Log.status(TAG, "connected to $host:$port")
        launchConnection(sock, incoming = false)
    }

    private fun launchConnection(socket: Socket, incoming: Boolean): Connection {
        // The side that dialled the socket is the Nearby Connections initiator: it sends
        // CONNECTION_REQUEST and drives UKEY2 as the client. The endpoint info and id are
        // the advertised ones, so the peer sees the identity it discovered.
        // Native session creation throws UnsatisfiedLinkError (an Error, so the
        // callers' catch (Exception) misses it) when the .so cannot load; surface
        // it as an IOException they already handle.
        val session = try {
            ShareSession(
                localName = localName,
                localEndpointInfo = localEndpointInfo,
                localEndpointId = localEndpointId,
                isInitiator = !incoming,
            )
        } catch (e: LinkageError) {
            try {
                socket.close()
            } catch (_: Exception) {
                // Already broken; the failure below is the useful one.
            }
            throw IOException("native share library unavailable", e)
        }
        val conn = Connection(
            socket = socket,
            session = session,
            incoming = incoming,
            remoteEndpoint = "${socket.inetAddress.hostAddress}:${socket.port}",
        )
        synchronized(connectionsLock) { byHandle[session.handle] = conn }
        publishConnections()
        conn.pumpJob = scope.launch {
            pump(conn)
        }
        return conn
    }

    // ------------------------------------------------------------------
    // Per-connection pump — raw stream, no Kotlin framing
    // ------------------------------------------------------------------

    // Broad catch is deliberate: the pump must mark the session failed rather than
    // throw, and socket plus native layers throw undocumented RuntimeExceptions.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun pump(conn: Connection) = withContext(Dispatchers.IO) {
        val session = conn.session
        val socket = conn.socket
        try {
            // Flush any initial outbound (e.g. UKEY2 ClientInit) before the first read.
            drainAll(conn)
            val input = socket.getInputStream()
            val buf = ByteArray(PUMP_BUFFER_SIZE)
            socket.soTimeout = PUMP_READ_TIMEOUT_MS
            val keeper = KeepAliveKeeper()
            while (isActive && !socket.isClosed) {
                if (pumpOnce(conn, input, buf, keeper) == PumpAction.Stop) break
            }
        } catch (e: Exception) {
            if (isActive) Log.status(TAG, "pump error for ${conn.remoteEndpoint}", e)
            conn.error.value = e.message
            try {
                conn.state.value = ShareState.Failed
            } catch (_: Exception) {
            }
        } finally {
            // Graceful shutdown: one last drain in each direction.
            drainReceived(conn)
            try {
                drainAll(conn)
            } catch (_: Exception) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
            receivedStore.closeSession(session.handle)
            conn.updateStateFromSession()
        }
    }

    /** Outcome of one [pumpOnce] iteration: keep looping or stop the pump. */
    private enum class PumpAction { Continue, Stop }

    /** Tracks the last keep-alive so [pumpOnce] stays a straight-line read. */
    private inner class KeepAliveKeeper(var lastKeepAlive: Long = System.currentTimeMillis()) {
        fun fireIfDue(conn: Connection) {
            val now = System.currentTimeMillis()
            if (now - lastKeepAlive < KEEP_ALIVE_EVERY_MS) return
            lastKeepAlive = now
            synchronized(conn.writeLock) {
                conn.session.sendKeepAlive()
                drainAll(conn)
            }
        }
    }

    /**
     * One pump iteration: a single read, feed, drain and state poll.
     *
     * Returns [PumpAction.Stop] when the peer closed, the frame was rejected, or the
     * session reached a terminal state — the single jump the loop is allowed.
     *
     * The `SocketTimeoutException` is the pump's periodic wake-up (see
     * [PUMP_READ_TIMEOUT_MS]), not an error: it is handled, never logged, which is
     * why it is swallowed here.
     */
    @Suppress("SwallowedException")
    private fun pumpOnce(
        conn: Connection,
        input: java.io.InputStream,
        buf: ByteArray,
        keeper: KeepAliveKeeper,
    ): PumpAction {
        val n: Int = try {
            input.read(buf)
        } catch (e: java.net.SocketTimeoutException) {
            // Periodic keep-alive: drain outbound even on read timeout so
            // handshake retries / accept responses still flush. A timeout here is
            // the expected wake-up, not an error — nothing to log or record.
            onPumpTimeout(conn, keeper)
            return pollTerminal(conn)
        }
        if (n == -1) {
            Log.status(TAG, "peer closed cleanly for ${conn.remoteEndpoint}")
            return PumpAction.Stop
        }
        return if (n == 0) PumpAction.Continue else handleInbound(conn, buf, n, keeper)
    }

    private fun onPumpTimeout(conn: Connection, keeper: KeepAliveKeeper) {
        keeper.fireIfDue(conn)
        drainAll(conn)
        conn.updateStateFromSession()
    }

    private fun pollTerminal(conn: Connection): PumpAction =
        if (conn.state.value.isTerminal) PumpAction.Stop else PumpAction.Continue

    private fun handleInbound(conn: Connection, buf: ByteArray, n: Int, keeper: KeepAliveKeeper): PumpAction {
        val inbound = buf.copyOf(n)
        val rc = conn.session.feedInbound(inbound)
        if (rc < 0) {
            Log.status(TAG, "feedInbound failed rc=$rc")
            conn.error.value = conn.session.failureReason ?: "Protocol error ($rc)"
            conn.state.value = ShareState.Failed
            return PumpAction.Stop
        }
        drainReceived(conn)
        drainAll(conn)
        keeper.fireIfDue(conn)
        conn.updateStateFromSession()
        return pollTerminal(conn)
    }

    /**
     * Move every chunk Rust has decrypted into the staging store, publishing a
     * [ReceivedFile] as each payload completes.
     *
     * Must run after every [ShareSession.feedInbound]: Rust drops each chunk as it
     * hands it over, so an undrained chunk is a lost chunk.
     */
    private fun drainReceived(conn: Connection) {
        val session = conn.session
        while (true) {
            val chunk = session.drainReceived() ?: return
            // Decrypted payload bytes, which is the only count comparable to the file sizes the
            // peer announced. Raw socket bytes include the UKEY2 handshake, frame prefixes,
            // encryption overhead and keep-alives, so a percentage built on them overshoots.
            conn.bytesReceived.value += chunk.body.size
            val finished = receivedStore.append(session.handle, chunk) ?: continue
            // The generic type counts as no answer: Rust substitutes it for an empty
            // `FileMetadata.mime_type`, which is what a GMS peer announces, and taking it at
            // face value left the extension fallback below unreachable.
            val announcedMime = conn.pendingFiles.value
                .firstOrNull { it.name == chunk.name }
                ?.mimeType
                ?.takeIf { it.isNotBlank() && it != ReceivedFileStore.GENERIC_MIME_TYPE }
            conn.receivedFiles.value += ReceivedFile(
                name = finished.name,
                sizeBytes = finished.length(),
                mimeType = announcedMime ?: ReceivedFileStore.mimeTypeOf(finished),
                uri = receivedStore.contentUri(finished),
            )
            Log.status(TAG, "received ${finished.name} (${finished.length()} bytes)")
        }
    }

    /**
     * Write everything Rust has queued, holding [Connection.writeLock] for the whole batch.
     *
     * The lock is the fix for two threads writing one socket: `sendFiles` runs on the
     * caller's coroutine while [pump] runs on this transport's scope, and both drain. Rust's
     * own state is mutex-protected, but two interleaved `OutputStream.write` calls put half
     * of one frame batch inside another, which the peer reads as a corrupt frame.
     *
     * A JVM monitor rather than a `kotlinx` `Mutex` because every caller is already doing
     * blocking socket I/O, and because this must also be callable from [pump]'s `finally`,
     * where suspending after cancellation is not allowed. It is reentrant, so a caller that
     * already holds it (see `keepAliveIfDue`) is safe.
     */
    internal fun drainAll(conn: Connection) {
        synchronized(conn.writeLock) {
            val out: OutputStream = conn.socket.getOutputStream()
            while (true) {
                val bytes = conn.session.drainOutbound() ?: break
                // Rust already applied the 4-byte big-endian length prefix to each frame and
                // concatenated them; write verbatim per PROTOCOL_CONTRACT.md §6.
                out.write(bytes)
                out.flush()
            }
        }
    }

    internal fun Connection.updateStateFromSession() {
        val polled = session.state
        if (peerName.value == null) session.peerName?.let { peerName.value = it }
        // Announced before the state that makes them worth showing, deliberately: a collector
        // watching `state` reacts to `AwaitingAccept` the moment it is published, and one that
        // saw an empty file list at that instant reported a transfer of no files.
        if (polled == ShareState.AwaitingAccept || polled == ShareState.Transferring) {
            val files = session.pendingFiles
            pendingFiles.value = files
            if (incoming) expectedTotalBytes.value = files.sumOf { it.sizeBytes }
        }
        state.value = polled
        if (polled == ShareState.Failed) {
            if (error.value == null) error.value = session.failureReason ?: "Transfer failed"
            Log.status(TAG, "session ${session.handle} failed: ${error.value}\n${session.trace}")
        }
    }

    suspend fun disconnect(conn: Connection) {
        // Joined, not just cancelled: the pump's `finally` still drains and polls the session,
        // so destroying it from under a pump that has not finished unwinding would have those
        // calls operate on a handle that no longer exists.
        conn.pumpJob?.cancelAndJoin()
        retire(conn)
    }

    /**
     * Release everything behind [conn] and drop it from [connections].
     *
     * Safe to call once the pump has stopped. Incoming sessions are retired by
     * `ShareReceiveNotifier` after it posts their terminal notification, because nothing else
     * ever disconnects them and both the `Connection` and its native session would otherwise
     * accumulate for the life of the process.
     */
    fun retire(conn: Connection) {
        try {
            conn.socket.close()
        } catch (_: Exception) {
        }
        receivedStore.closeSession(conn.session.handle)
        try {
            conn.session.destroy()
        } catch (_: Exception) {
        }
        synchronized(connectionsLock) { byHandle.remove(conn.session.handle) }
        publishConnections()
    }

    fun retire(handle: Long) {
        connectionFor(handle)?.let { retire(it) }
    }

    /**
     * Suspend until no session is still moving bytes.
     *
     * `connections` alone is not enough: it re-emits when a session appears or is retired, not
     * when one goes terminal, so the per-connection `state` flows have to be folded in.
     */
    @Suppress("OPT_IN_USAGE")
    suspend fun awaitIdle() {
        connections
            .flatMapLatest { conns ->
                if (conns.isEmpty()) flowOf(true)
                else combine(conns.map { it.state }) { states -> states.all { it.isTerminal } }
            }
            .first { it }
    }

    /**
     * Tear down the session [handle] identifies, if it is still alive.
     *
     * The handle-keyed entry point for a notification's Cancel action, which cannot carry a
     * [Connection].
     */
    suspend fun cancel(handle: Long) {
        val conn = connectionFor(handle) ?: return
        if (!conn.state.value.isTerminal) {
            conn.error.value = "cancelled"
            conn.state.value = ShareState.Failed
        }
        disconnect(conn)
    }

    fun release() {
        stopListening()
        synchronized(connectionsLock) {
            byHandle.values.forEach { c ->
                try {
                    c.socket.close()
                } catch (_: Exception) {
                }
                try {
                    c.session.destroy()
                } catch (_: Exception) {
                }
                c.pumpJob?.cancel()
            }
            byHandle.clear()
        }
        receivedStore.closeAll()
        _connections.value = emptyList()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // File streaming (on top of the Rust session's outbound/files API)
    // ------------------------------------------------------------------

    /**
     * Send the given [files] over [conn]'s session.
     *
     * Stages the metadata, announces it with an `INTRODUCTION` (held by Rust until the
     * paired-key exchange finishes), waits for the peer to accept, then streams each
     * file. The payload ids the peer sees come from the introduction, so the bytes match
     * the metadata it showed the user.
     *
     * At most one `INTRODUCTION` per session: picking more files after connecting used to
     * announce a second one on the same session, which the peer has no way to reconcile
     * with the payload ids it already showed its user.
     */
    suspend fun sendFiles(conn: Connection, files: List<File>) = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext
        if (!conn.introductionSent.compareAndSet(false, true)) {
            Log.status(TAG, "already announced files on session ${conn.sessionHandle}; ignoring ${files.size} more")
            return@withContext
        }
        stageFiles(conn, files) ?: return@withContext
        drainAll(conn)
        // Wait for the peer to accept before streaming a single chunk. Rust flips to
        // Transferring when the Sharing ConnectionResponse arrives; a real device hangs up
        // if payload chunks show up for a transfer its user has not accepted yet, and
        // openFile refuses with -2 in that state anyway.
        if (!awaitAccept(conn)) return@withContext
        for (file in files) {
            if (!streamFile(conn, file)) break
        }
    }

    /**
     * Answer the peer's `INTRODUCTION` on the session [handle] identifies.
     *
     * Received bytes go to app-private staging via [ReceivedFileStore], so there is no
     * destination to choose here; the user picks one later, per file, with Save.
     *
     * Returns -1 when no such session exists, which is the normal outcome for a
     * notification action tapped after the process was killed.
     */
    // Broad catch is deliberate: draining must not throw out of an accept path,
    // and the socket layer throws undocumented RuntimeExceptions.
    @Suppress("TooGenericExceptionCaught")
    fun acceptIncoming(handle: Long, accept: Boolean): Int {
        val conn = connectionFor(handle) ?: return -1
        val rc = conn.session.accept(accept)
        if (rc < 0) {
            conn.error.value = "accept failed ($rc)"
            return rc
        }
        conn.updateStateFromSession()
        // Flush the ACCEPT outbound immediately.
        try {
            drainAll(conn)
        } catch (e: Exception) {
            Log.status(TAG, "drain after accept failed", e)
        }
        return rc
    }
}

/**
 * A single peer connection + its Rust session pump.
 *
 * [sessionHandle] is the public identity: it is a plain `Long`, so unlike a `Connection` it
 * fits in a `PendingIntent` extra, and it is already the key [ReceivedFileStore] files
 * chunks under.
 */
class Connection(
    val socket: Socket,
    val session: ShareSession,
    val incoming: Boolean,
    val remoteEndpoint: String,
) : IncomingTransfer {
    override val sessionHandle: Long get() = session.handle

    /** Serialises every write to [socket]; see `TcpTransport.drainAll`. */
    internal val writeLock = Any()

    /** Guards against announcing a second `INTRODUCTION` on one session. */
    internal val introductionSent = java.util.concurrent.atomic.AtomicBoolean(false)

    var pumpJob: Job? = null
    override val state: MutableStateFlow<ShareState> = MutableStateFlow(ShareState.Handshaking)
    override val pendingFiles: MutableStateFlow<List<PendingFile>> = MutableStateFlow(emptyList())

    /** The peer's advertised device name, once its `CONNECTION_REQUEST` has been read. */
    val peerName: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Total bytes this transfer is expected to move, or 0 while unknown.
     *
     * Announced by the peer's `INTRODUCTION` when receiving and by our own when sending, so
     * a percentage is computable without the caller summing file sizes itself.
     */
    override val expectedTotalBytes: MutableStateFlow<Long> = MutableStateFlow(0L)

    /** Files that finished arriving, each staged in app-private storage. */
    override val receivedFiles: MutableStateFlow<List<ReceivedFile>> = MutableStateFlow(emptyList())
    val bytesSent: MutableStateFlow<Long> = MutableStateFlow(0L)

    /** Decrypted payload bytes received, comparable to [expectedTotalBytes]. */
    override val bytesReceived: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val error: MutableStateFlow<String?> = MutableStateFlow(null)

    /** A name to show a human: the peer's own, falling back to its address. */
    override val displayName: String get() = peerName.value ?: remoteEndpoint
}

/** No further frames will flow: the pump can stop and a terminal notification is due. */
val ShareState.isTerminal: Boolean
    get() = this == ShareState.Completed || this == ShareState.Failed
