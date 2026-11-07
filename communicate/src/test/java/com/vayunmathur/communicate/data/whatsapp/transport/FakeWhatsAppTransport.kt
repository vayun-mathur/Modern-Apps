package com.vayunmathur.communicate.data.whatsapp.transport

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [WhatsAppTransport]: no TCP, no Noise, no network.
 *
 * - Records every frame the client sends; [sentNodes] decodes them with
 *   [WhatsAppProtocol] so tests can assert on stanza shape.
 * - [emitNode]/[emitFrame] inject inbound (already-decrypted) frames, and
 *   [emitConnectionState] drives the client's connect/reconnect logic.
 * - When [iqResponder] is set, an outbound `<iq>` with an `id` is answered
 *   automatically: the responder's node is re-emitted inbound (through
 *   [messages], exactly as a server reply would arrive), so the client's
 *   pending-IQ correlation completes it.
 */
class FakeWhatsAppTransport : WhatsAppTransport {

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<WhatsAppSocket.ConnectionState>(extraBufferCapacity = 64)
    override val connectionState: SharedFlow<WhatsAppSocket.ConnectionState> = _connectionState.asSharedFlow()

    /** Every raw frame passed to [send], in order. */
    val sentFrames = CopyOnWriteArrayList<ByteArray>()

    @Volatile var connectCount = 0
        private set
    @Volatile var disconnectCount = 0
        private set

    /** What [send] returns; set to false to simulate a dead socket. */
    @Volatile var sendResult = true

    /** Builds the IQ result/error node for an outbound IQ, or null to leave it unanswered. */
    @Volatile var iqResponder: ((WhatsAppProtocol.Node) -> WhatsAppProtocol.Node?)? = null

    override fun connect() {
        connectCount++
        _connectionState.tryEmit(WhatsAppSocket.ConnectionState.Connecting)
    }

    override fun disconnect() {
        disconnectCount++
        _connectionState.tryEmit(WhatsAppSocket.ConnectionState.Disconnected("fake disconnect"))
    }

    override fun send(data: ByteArray): Boolean {
        sentFrames += data.copyOf()
        if (sendResult) maybeAnswerIq(data)
        return sendResult
    }

    private fun maybeAnswerIq(data: ByteArray) {
        val responder = iqResponder ?: return
        val node = runCatching { WhatsAppProtocol.decodeNode(data) }.getOrNull() ?: return
        if (node.tag != "iq") return
        val id = node.attrs["id"] ?: return
        val response = responder(node) ?: return
        // Keep the request's id unless the responder set one explicitly.
        val withId = if (response.attrs.containsKey("id")) response else response.copy(attrs = response.attrs + ("id" to id))
        emitNode(withId)
    }

    /** All sent frames decoded as protocol nodes (undecodable frames are skipped). */
    fun sentNodes(): List<WhatsAppProtocol.Node> =
        sentFrames.mapNotNull { runCatching { WhatsAppProtocol.decodeNode(it) }.getOrNull() }

    fun lastSentNode(tag: String): WhatsAppProtocol.Node? = sentNodes().lastOrNull { it.tag == tag }

    fun clearSent() = sentFrames.clear()

    fun emitFrame(data: ByteArray) {
        _messages.tryEmit(data.copyOf())
    }

    fun emitNode(node: WhatsAppProtocol.Node) {
        emitFrame(WhatsAppProtocol.encodeNode(node))
    }

    fun emitConnectionState(state: WhatsAppSocket.ConnectionState) {
        _connectionState.tryEmit(state)
    }

    /** Suspends until at least [count] nodes with [tag] have been sent. */
    suspend fun awaitSent(tag: String, count: Int = 1, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sentNodes().count { it.tag == tag } >= count) return
            delay(10)
        }
        throw AssertionError("timed out waiting for $count <$tag> sent; sent so far: ${sentNodes().map { it.tag }}")
    }
}
