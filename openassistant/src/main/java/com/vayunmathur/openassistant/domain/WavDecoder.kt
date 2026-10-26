package com.vayunmathur.openassistant.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val WAV_SAMPLE_RATE = 16_000
const val WAV_MAX_SECONDS = 30

private const val RIFF_HEADER = 12
private const val CHUNK_HEADER = 8
private const val FMT_MIN = 16
private const val PCM = 1
private const val BITS = 16
private const val PCM_SCALE = 32768f

/**
 * 16 kHz mono 16-bit PCM samples from a WAV file's bytes, scaled to -1..1 and capped at
 * [maxSamples]; null for anything else. There is no resampler, so other formats are refused.
 */
fun decodeWavMono16k(bytes: ByteArray, maxSamples: Int = WAV_SAMPLE_RATE * WAV_MAX_SECONDS): FloatArray? {
    if (bytes.size < RIFF_HEADER || chunkId(bytes, 0) != "RIFF" || chunkId(bytes, 8) != "WAVE") return null
    val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var described = false
    var dataAt = -1
    var dataBytes = 0
    var at = RIFF_HEADER
    while (at + CHUNK_HEADER <= bytes.size) {
        val size = wav.getInt(at + 4)
        if (size < 0) break
        val body = at + CHUNK_HEADER
        when (chunkId(bytes, at)) {
            "fmt " -> {
                if (size < FMT_MIN || body + FMT_MIN > bytes.size) return null
                val ok = wav.getShort(body).toInt() == PCM &&
                    wav.getShort(body + 2).toInt() == 1 &&
                    wav.getInt(body + 4) == WAV_SAMPLE_RATE &&
                    wav.getShort(body + 14).toInt() == BITS
                if (!ok) return null
                described = true
            }
            "data" -> {
                dataAt = body
                dataBytes = minOf(size, bytes.size - body)
            }
        }
        at = body + size + (size and 1)
    }
    if (!described || dataAt < 0) return null
    val count = minOf(dataBytes / 2, maxSamples)
    val pcm = ByteBuffer.wrap(bytes, dataAt, dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return FloatArray(count) { pcm.get(it) / PCM_SCALE }
}

private fun chunkId(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
