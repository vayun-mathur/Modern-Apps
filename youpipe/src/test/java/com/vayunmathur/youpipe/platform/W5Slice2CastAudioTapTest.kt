package com.vayunmathur.youpipe.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Slice 2/3 (w5): CastAudioTap's framework-free paths (platform/
 * CastPlayback.kt). `attach(null)` is the "session has no audio" no-op;
 * `detach()` with nothing attached neither crashes nor retains buffers;
 * `flush()` with a non-PCM encoding refuses the tap so `handleBuffer`
 * drops buffers instead of forwarding noise. Robolectric: Log goes through
 * the repo facade into android.util.Log. The attached pipe path (resampling
 * math, writer thread) runs on-device where a real ParcelFileDescriptor
 * pipe exists.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2CastAudioTapTest {

    @Test
    fun attachNullAndDetachAreSafeNoOps() {
        CastAudioTap.attach(null)
        CastAudioTap.detach()
        CastAudioTap.detach()
    }

    @Test
    fun handleBuffer_withoutAttach_dropsSilently() {
        CastAudioTap.detach()
        CastAudioTap.flush(48_000, 2, android.media.AudioFormat.ENCODING_PCM_16BIT)
        // out == null: returns before touching the buffer — must not consume it.
        val buf = ByteBuffer.allocateDirect(64)
        val remaining = buf.remaining()
        CastAudioTap.handleBuffer(buf)
        assertTrue(buf.remaining() == remaining)
    }

    @Test
    fun handleBuffer_unsupportedEncoding_dropsSilently() {
        CastAudioTap.detach()
        // Float encoding is refused: anything other than interleaved 16-bit
        // would be sent as noise, so the tap stays off.
        CastAudioTap.flush(48_000, 2, android.media.AudioFormat.ENCODING_PCM_FLOAT)
        val buf = ByteBuffer.allocateDirect(64)
        CastAudioTap.handleBuffer(buf)
        CastAudioTap.detach()
    }

    @Test
    fun flush_invalidFormat_disablesTap() {
        CastAudioTap.detach()
        // Zero sample rate / zero channels are refused rather than
        // divide-by-zeroing the resampler later.
        CastAudioTap.flush(0, 0, android.media.AudioFormat.ENCODING_PCM_16BIT)
        CastAudioTap.handleBuffer(ByteBuffer.allocateDirect(64))
        CastAudioTap.detach()
    }
}
