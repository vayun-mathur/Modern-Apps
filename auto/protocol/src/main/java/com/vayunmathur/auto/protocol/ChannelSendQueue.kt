package com.vayunmathur.auto.protocol

/**
 * One framed message waiting for the wire: the message-codec payload plus the flags its
 * frame needs. Enqueued by any thread, drained only under the connection's engine lock.
 */
class QueuedSend(
    val channelId: Int,
    /** Message-codec bytes (`[type][protobuf]`), not yet framed or TLS-wrapped. */
    val payload: ByteArray,
    val isControl: Boolean,
    val encrypted: Boolean,
)

/**
 * Per-channel send queues with control priority.
 *
 * Why this exists: a video burst must never head-block control traffic or acks. Every
 * `send()` enqueues here; the drain takes channel 0 first (control opens, discovery,
 * ping responses), then round-robins the service channels so a ch2 video flood cannot
 * starve a ch6 ack or a ch8 key binding.
 *
 * Pure JVM, no I/O: the connection owns one of these and drains it under its SSLEngine
 * lock, which is what keeps the single-engine discipline while senders run on any
 * thread (vsync drain, audio sinks, input injection). All methods are synchronized;
 * payloads are never copied, only referenced.
 */
class ChannelSendQueue {
    private val lock = Any()

    /** Channel 0: control opens, discovery, ping responses. Always drains first. */
    private val controlQueue = ArrayDeque<QueuedSend>()

    /** Service channels by id, plus the round-robin order of the non-empty ones. */
    private val channelQueues = LinkedHashMap<Int, ArrayDeque<QueuedSend>>()
    private val channelOrder = ArrayDeque<Int>()

    /** Enqueues [send] on its channel's queue. Thread-safe, never blocks. */
    fun enqueue(send: QueuedSend) = synchronized(lock) {
        if (send.channelId == CONTROL_CHANNEL_ID) {
            controlQueue.addLast(send)
            return@synchronized
        }
        val queue = channelQueues.getOrPut(send.channelId) { ArrayDeque() }
        if (queue.isEmpty()) channelOrder.addLast(send.channelId)
        queue.addLast(send)
    }

    /**
     * Removes and returns the next send: channel 0 first, then service channels in
     * round-robin order, FIFO within each channel. Null when empty.
     */
    fun poll(): QueuedSend? = synchronized(lock) {
        if (controlQueue.isNotEmpty()) return controlQueue.removeFirst()
        while (channelOrder.isNotEmpty()) {
            val send = pollNextServiceSend() ?: continue
            return send
        }
        return null
    }

    /**
     * Advances the round-robin by one channel: drops drained or orphaned
     * entries, re-queues still-full ones, and returns the head send when the
     * channel had one. Null means "no send here, keep scanning".
     */
    private fun pollNextServiceSend(): QueuedSend? {
        val id = channelOrder.removeFirst()
        val queue = channelQueues[id] ?: return null
        val send = queue.removeFirstOrNull()
        if (send == null) {
            channelQueues.remove(id)
            return null
        }
        // A still-full channel goes to the back; a drained one leaves the rotation.
        if (queue.isNotEmpty()) channelOrder.addLast(id) else channelQueues.remove(id)
        return send
    }

    /** Total sends waiting, across all channels. */
    fun pendingCount(): Int = synchronized(lock) {
        controlQueue.size + channelQueues.values.sumOf { it.size }
    }

    /** Total payload bytes waiting. Telemetry only; framing/TLS overhead is extra. */
    fun pendingBytes(): Long = synchronized(lock) {
        controlQueue.sumOf { it.payload.size.toLong() } +
            channelQueues.values.sumOf { queue -> queue.sumOf { it.payload.size.toLong() } }
    }

    fun isEmpty(): Boolean = synchronized(lock) {
        controlQueue.isEmpty() && channelQueues.values.all { it.isEmpty() }
    }

    /** Drops everything unsent. Called when the session ends; a new socket starts clean. */
    fun clear() = synchronized(lock) {
        controlQueue.clear()
        channelQueues.clear()
        channelOrder.clear()
    }

    /** Well-known channel ids for the send queue's priority order. */
    companion object {
        /** The control channel: its queue always drains ahead of every service channel. */
        const val CONTROL_CHANNEL_ID = 0
    }
}
