package com.vayunmathur.openassistant.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WavDecoderTest {

    private fun wav(samples: ShortArray, rate: Int = 16_000, channels: Int = 1, bits: Int = 16, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 5 + 1 else 0
        val data = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + extra + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + extra + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort())
            .putShort(bits.toShort())
        if (extraChunk) buffer.put("LIST".toByteArray()).putInt(5).put(ByteArray(5 + 1))
        buffer.put("data".toByteArray()).putInt(data)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    @Test
    fun decodesTheRecordersCanonicalHeader() {
        val out = assertNotNull(decodeWavMono16k(wav(shortArrayOf(0, 16384, -32768, 32767))))
        assertEquals(listOf(0f, 0.5f, -1f, 32767 / 32768f), out.toList())
    }

    @Test
    fun skipsUnknownChunksIncludingOddPadding() {
        val out = assertNotNull(decodeWavMono16k(wav(shortArrayOf(100, 200), extraChunk = true)))
        assertEquals(2, out.size)
    }

    @Test
    fun capsAtTheSampleLimit() {
        val out = assertNotNull(decodeWavMono16k(wav(ShortArray(10)), maxSamples = 4))
        assertEquals(4, out.size)
    }

    @Test
    fun refusesOtherFormats() {
        assertNull(decodeWavMono16k(wav(ShortArray(4), rate = 44_100)))
        assertNull(decodeWavMono16k(wav(ShortArray(4), channels = 2)))
        assertNull(decodeWavMono16k(wav(ShortArray(4), bits = 8)))
        assertNull(decodeWavMono16k(ByteArray(10)))
        assertNull(decodeWavMono16k("RIFF0000WAVE".toByteArray()))
    }
}
