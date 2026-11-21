package com.vayunmathur.games.voxels.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Binary Rust -> Kotlin outbound frame ([OutboundFrame]): chunk payloads
 * cross JNI as raw bytes, and the signed-op JSON reconstructed from them is
 * byte-identical to the legacy JSON-number representation, so signed ops,
 * PendingOps persistence/replay and the relay wire format do not change.
 */
class OutboundFrameTest {

    /** Builds a frame exactly as Rust `encode_outbound_bin` does (spec in OutboundFrame). */
    private fun frameOf(vararg msgs: OutboundFrame.Msg): ByteArray {
        val out = mutableListOf<Byte>()
        fun u32(v: Int) = repeat(4) { out += (v shr (8 * it)).toByte() }
        out += 'V'.code.toByte(); out += 'X'.code.toByte(); out += 'B'.code.toByte(); out += '1'.code.toByte()
        u32(msgs.size)
        for (m in msgs) when (m) {
            is OutboundFrame.Msg.Chunk -> {
                out += 1; out += m.dim.toByte(); u32(m.cx); u32(m.cz); u32(m.data.size)
                out += m.data.toList()
            }
            is OutboundFrame.Msg.SignedJson -> {
                out += 0; val b = m.text.encodeToByteArray(); u32(b.size); out += b.toList()
            }
            is OutboundFrame.Msg.TransformJson -> {
                out += 2; val b = m.text.encodeToByteArray(); u32(b.size); out += b.toList()
            }
        }
        return out.toByteArray()
    }

    private fun chunk(data: ByteArray) = OutboundFrame.Msg.Chunk(1, -2, 3, data)

    @Test fun chunkRoundTripsByteEqualAtAllSizes() {
        for (data in listOf(
            ByteArray(0),
            byteArrayOf(0xAB.toByte()),
            ByteArray(128 * 1024) { (it % 251).toByte() },
        )) {
            val decoded = OutboundFrame.decode(frameOf(chunk(data)))
            assertEquals(1, decoded.size)
            val back = decoded.single() as OutboundFrame.Msg.Chunk
            assertEquals(1, back.dim); assertEquals(-2, back.cx); assertEquals(3, back.cz)
            assertTrue(data.contentEquals(back.data), "chunk bytes changed at size ${data.size}")
        }
    }

    @Test fun goldenFrameDecodesAndOpsJsonIsByteIdentical() {
        // Same golden bytes pinned in Rust net_bin tests.
        val golden = "VXB1".encodeToByteArray() + byteArrayOf(
            1, 0, 0, 0, 1, 1, -2, -1, -1, -1, 3, 0, 0, 0, 2, 0, 0, 0, 0, -1
        )
        val msg = OutboundFrame.decode(golden).single() as OutboundFrame.Msg.Chunk
        assertTrue(byteArrayOf(0, -1).contentEquals(msg.data))
        // The signed string: exactly the legacy serde/JSONObject representation.
        assertEquals(
            """{"type":"ChunkData","dim":1,"cx":-2,"cz":3,"data":[0,255]}""",
            msg.toOpsJson()
        )
        assertEquals(
            """{"type":"ChunkData","dim":1,"cx":-2,"cz":3,"data":[]}""",
            chunk(ByteArray(0)).toOpsJson()
        )
        assertEquals(
            """{"type":"ChunkData","dim":1,"cx":-2,"cz":3,"data":[171]}""",
            chunk(byteArrayOf(0xAB.toByte())).toOpsJson()
        )
    }

    @Test fun mixedBatchKeepsOrderAndKinds() {
        val json = OutboundFrame.Msg.SignedJson("""{"type":"BlockEdit","dim":0,"x":3,"y":64,"z":-7,"id":12,"meta":5}""")
        val transform = OutboundFrame.Msg.TransformJson("""{"type":"PlayerTransform","device":"d1"}""")
        val decoded = OutboundFrame.decode(frameOf(json, chunk(byteArrayOf(9)), transform))
        assertEquals(listOf(json, transform), decoded.filter { it !is OutboundFrame.Msg.Chunk })
        assertEquals(chunk(byteArrayOf(9)), decoded[1])
    }

    @Test fun corruptFramesDecodeAsEmpty() {
        assertTrue(OutboundFrame.decode(ByteArray(0)).isEmpty())
        assertTrue(OutboundFrame.decode("NOPE........".encodeToByteArray()).isEmpty())
        val good = frameOf(chunk(byteArrayOf(1, 2, 3)))
        assertTrue(OutboundFrame.decode(good.copyOf(good.size - 1)).isEmpty(), "truncated")
        assertTrue(OutboundFrame.decode(good + byteArrayOf(0)).isEmpty(), "trailing byte")
    }

    @Test fun binaryIsRoughlyBytePerByteWhereJsonWasThreePlus() {
        val data = ByteArray(128 * 1024) { (it % 256).toByte() }
        val bin = frameOf(chunk(data))
        val opsJson = chunk(data).toOpsJson()
        // Frame: 1 byte/byte + 22 header bytes. Signed JSON: ~2-4 chars/byte.
        assertEquals(data.size + 22, bin.size)
        assertTrue(opsJson.length > bin.size * 2, "JSON ${opsJson.length} vs binary ${bin.size}")
    }

    /** Pending-ops replay is untouched: a signed chunk op persists + replays verbatim. */
    @Test fun pendingOpsReplayOfSignedChunkOpIsUnchanged() = runBlocking {
        val signedChunkOp = """{"author":"dev","sig":"AAAA","ops":""" +
            "\"" + chunk(byteArrayOf(0, -1)).toOpsJson().replace("\"", "\\\"") + "\"}"
        val store = InMemoryPendingOpsStore()
        val sent = mutableListOf<List<String>>()
        var online = false
        val queue = PendingOpQueue(store) { ops -> if (online) { sent += ops; true } else false }
        assertEquals(1, queue.enqueueAndFlush(listOf(signedChunkOp)))
        assertEquals(listOf(signedChunkOp), store.load())
        online = true
        val replayed = PendingOpQueue(store) { ops -> sent += ops; true }
        assertEquals(0, replayed.flushPending())
        assertEquals(listOf(listOf(signedChunkOp)), sent)
        assertTrue(store.load().isEmpty())
    }
}
