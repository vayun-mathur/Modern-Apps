package com.vayunmathur.speech.testing

import android.media.AudioRecord
import java.util.concurrent.ConcurrentLinkedQueue
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Scripts the microphone: each read fills the buffer with the next queued
 * amplitude (100 ms of constant samples) and returns a full chunk. When the
 * script runs out, reads return silence, which drives the VAD to its
 * end-of-utterance / no-speech exits. A queued negative value (other than
 * [BLOCK]) is returned as-is to simulate an AudioRecord read error.
 */
@Implements(AudioRecord::class)
class FakeAudioRecordShadow {

    @Implementation
    fun __constructor__(audioSource: Int, sampleRateInHz: Int, channelConfig: Int, audioFormat: Int, bufferSizeInBytes: Int) = Unit

    @Implementation
    fun getState(): Int = AudioRecord.STATE_INITIALIZED

    @Implementation
    fun startRecording() = Unit

    @Implementation
    fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        val amplitude = script.poll() ?: 0
        if (amplitude == BLOCK) {
            blockEntered?.countDown()
            blockRelease?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            for (i in 0 until sizeInShorts) audioData[offsetInShorts + i] = 0
        } else if (amplitude < 0) {
            reads++
            return amplitude
        } else {
            for (i in 0 until sizeInShorts) audioData[offsetInShorts + i] = amplitude.toShort()
        }
        reads++
        return sizeInShorts
    }

    @Implementation
    fun stop() = Unit

    @Implementation
    fun release() { released = true }

    companion object {
        /** Script sentinel: this read blocks until [releaseBlock] is called. */
        const val BLOCK = Int.MIN_VALUE

        private val script = ConcurrentLinkedQueue<Int>()
        @Volatile var reads = 0
        @Volatile var released = false
        @Volatile var blockEntered: java.util.concurrent.CountDownLatch? = null
        @Volatile var blockRelease: java.util.concurrent.CountDownLatch? = null

        fun reset(amplitudes: List<Int>) {
            script.clear()
            script.addAll(amplitudes)
            reads = 0
            released = false
            blockEntered = java.util.concurrent.CountDownLatch(1)
            blockRelease = java.util.concurrent.CountDownLatch(1)
        }

        fun releaseBlock() { blockRelease?.countDown() }

        @Implementation
        @JvmStatic
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 3200
    }
}
