package com.vayunmathur.cast.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.CodecSelection
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefusalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `demoted codec shared by both ends gets demotion message`() {
        val msg = refusal(
            context,
            CodecSelection.None(
                senderOffered = listOf(VideoCodec.Av1),
                receiverOffered = listOf(VideoCodec.Av1),
                demoted = setOf(VideoCodec.Av1),
            ),
        )
        assertTrue(msg.isNotBlank())
        assertTrue(msg.contains(VideoCodec.Av1.label), msg)
    }

    @Test
    fun `no sender codec names phone as short`() {
        val msg = refusal(
            context,
            CodecSelection.None(emptyList(), listOf(VideoCodec.Hevc), emptySet()),
        )
        assertTrue(msg.isNotBlank())
        assertTrue(msg.contains(VideoCodec.Av1.label) && msg.contains(VideoCodec.Hevc.label), msg)
    }

    @Test
    fun `no receiver codec names tv as short`() {
        val msg = refusal(
            context,
            CodecSelection.None(listOf(VideoCodec.Hevc), emptyList(), emptySet()),
        )
        assertTrue(msg.isNotBlank())
    }

    @Test
    fun `disjoint offers name both sides`() {
        val msg = refusal(
            context,
            CodecSelection.None(listOf(VideoCodec.Av1), listOf(VideoCodec.Hevc), emptySet()),
        )
        assertTrue(msg.contains(VideoCodec.Av1.label), msg)
        assertTrue(msg.contains(VideoCodec.Hevc.label), msg)
    }

    @Test
    fun `chosen outcome exposes codec`() {
        val chosen = CodecSelection.Chosen(
            VideoCodec.Hevc,
            com.vayunmathur.cast.protocol.CodecLimits(VideoCodec.Hevc, 1920, 1080, 60, 1000),
            1000,
        )
        assertEqualsCodec(chosen)
    }

    private fun assertEqualsCodec(chosen: CodecSelection.Chosen) {
        kotlin.test.assertEquals(VideoCodec.Hevc, CodecOutcome.Chosen(chosen).codec)
        kotlin.test.assertEquals("why", CodecOutcome.Refused("why").message)
    }
}
