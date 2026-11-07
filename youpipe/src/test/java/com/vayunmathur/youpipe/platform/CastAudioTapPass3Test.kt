package com.vayunmathur.youpipe.platform

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: the PCM tap's conversion and pipe behaviour, no session needed. */
@RunWith(AndroidJUnit4::class)
class CastAudioTapPass3Test {

    @AfterTest
    fun detach() {
        CastAudioTap.detach()
        CastAudioTap.detach()
    }

    private fun pcm16(vararg samples: Int): ByteBuffer {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return ByteBuffer.wrap(bytes)
    }

    private fun readUntil(input: FileInputStream, count: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 10_000
        val buf = ByteArray(4096)
        while (out.size() < count && System.currentTimeMillis() < deadline) {
            val available = input.available()
            if (available > 0) {
                val n = input.read(buf, 0, minOf(available, minOf(buf.size, count - out.size())))
                if (n > 0) out.write(buf, 0, n)
            } else {
                Thread.sleep(10)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun passthroughAtContractRateCopiesWithoutConsuming() {
        val pipe = ParcelFileDescriptor.createPipe()
        CastAudioTap.attach(pipe[1])
        CastAudioTap.flush(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        val reader = FileInputStream(pipe[0].fileDescriptor)
        val buffer = pcm16(1000, -1000, 2000, -2000)
        CastAudioTap.handleBuffer(buffer)
        assertEquals(0, buffer.position(), "the caller's buffer must not be consumed")
        val got = readUntil(reader, 8)
        assertTrue(byteArrayOf(0xE8.toByte(), 0x03, 0x18, 0xFC.toByte(), 0xD0.toByte(), 0x07, 0x30, 0xF8.toByte()).contentEquals(got), got.toList().toString())
        reader.close()
    }

    @Test
    fun mono441ResamplesToStereo48k() {
        val pipe = ParcelFileDescriptor.createPipe()
        CastAudioTap.attach(pipe[1])
        CastAudioTap.flush(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        val reader = FileInputStream(pipe[0].fileDescriptor)
        // 441 mono frames of a constant 500: 480 stereo frames of (500, 500).
        val samples = IntArray(441) { 500 }
        CastAudioTap.handleBuffer(pcm16(*samples))
        val got = readUntil(reader, 480 * 4)
        assertEquals(480 * 4, got.size)
        for (frame in 0 until 480) {
            val left = (got[frame * 4].toInt() and 0xFF) or (got[frame * 4 + 1].toInt() shl 8)
            val right = (got[frame * 4 + 2].toInt() and 0xFF) or (got[frame * 4 + 3].toInt() shl 8)
            assertEquals(500, left, "frame $frame left")
            assertEquals(500, right, "frame $frame right")
        }
        reader.close()
    }

    @Test
    fun unsupportedFormatsAndEmptyBuffersAreDropped() {
        val pipe = ParcelFileDescriptor.createPipe()
        CastAudioTap.attach(pipe[1])
        CastAudioTap.flush(48_000, 2, AudioFormat.ENCODING_PCM_FLOAT)
        CastAudioTap.handleBuffer(pcm16(1, 2, 3, 4))
        CastAudioTap.flush(0, 0, AudioFormat.ENCODING_PCM_16BIT)
        CastAudioTap.handleBuffer(pcm16(1, 2, 3, 4))
        CastAudioTap.flush(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        CastAudioTap.handleBuffer(ByteBuffer.allocate(0))
        // A lone byte at a resample rate has no whole frame to convert.
        CastAudioTap.flush(44_100, 2, AudioFormat.ENCODING_PCM_16BIT)
        CastAudioTap.handleBuffer(ByteBuffer.wrap(byteArrayOf(7)))
        val reader = FileInputStream(pipe[0].fileDescriptor)
        Thread.sleep(250)
        assertEquals(0, reader.available(), "nothing unsupported may reach the pipe")
        reader.close()
    }

    @Test
    fun queueOverflowDropsInsteadOfBlocking() {
        val pipe = ParcelFileDescriptor.createPipe()
        CastAudioTap.attach(pipe[1])
        CastAudioTap.flush(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        // Far more than the bounded queue holds, with nobody reading yet:
        // every call must come back (this would hang forever if it blocked).
        repeat(64) { CastAudioTap.handleBuffer(pcm16(it, it, it, it)) }
        val reader = FileInputStream(pipe[0].fileDescriptor)
        val got = readUntil(reader, 8)
        assertEquals(8, got.size)
        reader.close()
    }

    @Test
    fun attachNullAndReattachAreSafe() {
        CastAudioTap.attach(null)
        CastAudioTap.handleBuffer(pcm16(1, 2))
        val first = ParcelFileDescriptor.createPipe()
        val second = ParcelFileDescriptor.createPipe()
        CastAudioTap.attach(first[1])
        CastAudioTap.flush(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        CastAudioTap.attach(second[1]) // replaces the first pipe
        CastAudioTap.handleBuffer(pcm16(9, 9, 9, 9))
        val reader = FileInputStream(second[0].fileDescriptor)
        assertEquals(8, readUntil(reader, 8).size)
        reader.close()
        runCatching { first[0].close() }
    }
}
