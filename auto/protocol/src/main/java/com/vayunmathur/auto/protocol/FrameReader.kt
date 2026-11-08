package com.vayunmathur.auto.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** A message reassembled from one or more frames, ready for [MessageCodec.decode]. */
class ReassembledMessage(
    val channelId: Int,
    val isControl: Boolean,
    val payload: ByteArray,
)

/**
 * Turns a byte stream into whole GAL messages.
 *
 * Feed it whatever the transport hands over — the boundaries are arbitrary and a single
 * read can contain half a header or several frames — and it emits messages once their last
 * fragment arrives.
 *
 * Decryption happens per frame, before reassembly, which is the order gearhead uses: the
 * frame's length field counts ciphertext, while the reassembly buffer accumulates plaintext.
 * Getting that backwards would size the buffer against the wrong number.
 *
 * @param decrypt unwraps one frame payload. The default is identity, for the plaintext
 *   frames exchanged before the TLS handshake completes and for tests.
 * @param onFramingError fires when an encrypted frame fails to decrypt. Corrupt
 *   ciphertext is malformed input, and gearhead's `rtr` answers with a 2-byte `0xFFFF`
 *   framing-error control frame and tears down rather than carrying a poisoned
 *   reassembly buffer -- so the reader drops the frame and lets the owner tear down.
 *   Decryption still happens per frame before reassembly; only the failure gains a path.
 */
class FrameReader(
    private val decrypt: (ByteArray) -> ByteArray = { it },
    private val onFramingError: () -> Unit = {},
) {
    private var pending = ByteBuffer.allocate(0)
    private val partial = mutableMapOf<Int, ByteArrayOutputStream>()

    /** Appends [count] bytes from [bytes] and returns whatever became complete. */
    fun offer(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size): List<ReassembledMessage> {
        append(bytes, offset, count)
        val completed = mutableListOf<ReassembledMessage>()
        while (true) {
            when (val step = takeFrame()) {
                is FrameStep.Completed -> completed += step.message
                FrameStep.Consumed -> Unit // a fragment (or dropped bad frame); keep draining
                FrameStep.NeedMore -> break
            }
        }
        return completed
    }

    /** Outcome of one [takeFrame] attempt; [Consumed] must not stop the drain. */
    private sealed interface FrameStep {
        data class Completed(val message: ReassembledMessage) : FrameStep
        data object Consumed : FrameStep
        data object NeedMore : FrameStep
    }

    private fun append(bytes: ByteArray, offset: Int, count: Int) {
        val combined = ByteBuffer.allocate(pending.remaining() + count)
        combined.put(pending)
        combined.put(bytes, offset, count)
        combined.flip()
        pending = combined
    }

    /**
     * Consumes one frame if a whole one is buffered.
     *
     * @return the message if that frame completed one, null if more bytes are needed or the
     *   frame was a non-final fragment.
     */
    private fun takeFrame(): FrameStep {
        if (pending.remaining() < FrameHeader.SHORT_HEADER_SIZE) return FrameStep.NeedMore
        // The first fragment of a fragmented message carries an 8-byte long
        // header. A read can end inside it (4-7 bytes in), and FrameHeader
        // throws rather than waiting in that case, so wait for all of it.
        val flags = pending.get(pending.position() + 1).toInt() and 0xFF
        val isFirstFragment = flags and FrameFlags.FIRST != 0 && flags and FrameFlags.LAST == 0
        if (isFirstFragment && pending.remaining() < FrameHeader.LONG_HEADER_SIZE) {
            return FrameStep.NeedMore
        }

        pending.mark()
        val header = FrameHeader.readFrom(pending)
        if (pending.remaining() < header.payloadLength) {
            // Not all here yet; rewind and wait for more.
            pending.reset()
            return FrameStep.NeedMore
        }

        val raw = ByteArray(header.payloadLength)
        pending.get(raw)
        compact()

        // A frame that fails to decrypt is malformed input, not a short read:
        // drop it and fire the teardown hook rather than feeding garbage to
        // reassembly or throwing out of the pump loop.
        val plaintext = if (header.isEncrypted) {
            runCatching { decrypt(raw) }.getOrElse {
                onFramingError()
                return FrameStep.Consumed
            }
        } else {
            raw
        }

        if (header.isFirst && header.isLast) {
            return FrameStep.Completed(
                ReassembledMessage(header.channelId, header.isControl, plaintext),
            )
        }

        // `totalLength` is only a capacity hint. The stream grows as needed rather than
        // trusting it, because the field counts one of plaintext or ciphertext depending on
        // where you read it and being wrong there would truncate a message. It is
        // also peer-controlled: a negative value made ByteArrayOutputStream throw
        // and a huge one tried to allocate gigabytes up front, so only a sane
        // range is honoured as the initial capacity and anything else falls
        // back to this fragment's size (the stream still grows to fit).
        val hint = header.totalLength?.takeIf { it in 0..MAX_INITIAL_CAPACITY }
        val buffer = partial.getOrPut(header.channelId) {
            ByteArrayOutputStream(hint ?: plaintext.size)
        }
        buffer.write(plaintext)

        if (!header.isLast) return FrameStep.Consumed
        partial.remove(header.channelId)
        return FrameStep.Completed(
            ReassembledMessage(header.channelId, header.isControl, buffer.toByteArray()),
        )
    }

    /** Drops consumed bytes so [pending] does not grow without bound. */
    private fun compact() {
        pending = if (pending.hasRemaining()) {
            ByteBuffer.allocate(pending.remaining()).put(pending).also { it.flip() }
        } else {
            EMPTY
        }
    }

    /** True when a fragmented message is still being assembled on some channel. */
    val hasPartialMessages: Boolean get() = partial.isNotEmpty()

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /** Largest peer-supplied totalLength honoured as an allocation hint. */
        const val MAX_INITIAL_CAPACITY = 64 * 1024
    }
}
