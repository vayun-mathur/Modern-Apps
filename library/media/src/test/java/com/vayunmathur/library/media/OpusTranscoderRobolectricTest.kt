package com.vayunmathur.library.media

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The failure paths of the transcoder that do not need a real codec: under
 * Robolectric the extractor finds no tracks, which is exactly the "no
 * decodable audio" case a caller has to handle. The happy path runs on a
 * device in CodecPipelineTest.
 */
@RunWith(RobolectricTestRunner::class)
class OpusTranscoderRobolectricTest {

    @Test
    fun `transcode of undecodable bytes returns null`() {
        assertNull(OpusTranscoder.transcode(ByteArray(256) { 0x42 }))
        assertNull(OpusTranscoder.transcode(ByteArray(0)))
    }

    @Test
    fun `transcodeTo of undecodable bytes writes nothing and returns null`() {
        val sink = ByteArrayOutputStream()
        assertNull(OpusTranscoder.transcodeTo(ByteArray(256) { 0x42 }, sink))
        assertEquals(0, sink.size())
    }

    @Test
    fun `transcode constants are the documented stream values`() {
        assertEquals(1_000_000L, OpusTranscoder.US_PER_SECOND)
        assertEquals("OpusTranscoder", OpusTranscoder.TAG)
    }
}
