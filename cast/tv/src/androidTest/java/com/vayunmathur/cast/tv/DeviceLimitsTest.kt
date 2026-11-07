package com.vayunmathur.cast.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.cast.tv.platform.AudioPlayer
import com.vayunmathur.cast.tv.platform.NetworkLabel
import com.vayunmathur.cast.tv.platform.PanelModes
import com.vayunmathur.cast.tv.platform.VideoDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real panel, codec list and network interfaces: what the JVM shadows cannot enumerate. */
@RunWith(AndroidJUnit4::class)
class DeviceLimitsTest {

    @Test fun panelModesAndNetworkLabelOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modes = PanelModes.of(context)
        assertTrue("emulator panel should report modes", modes.isNotEmpty())
        assertTrue(modes.zipWithNext().all { (a, b) -> a.area >= b.area }) // sorted by area desc
        NetworkLabel.of(context) // ethernet emulator: IP half at least must not throw
    }

    @Test fun decoderLimitsOnDevice() {
        val limits = VideoDecoder.limits()
        // Whatever the emulator enumerates, the advertised limits must be self-consistent.
        for (codec in limits.videoCodecs) {
            assertTrue(codec.maxWidth > 0 && codec.maxHeight > 0)
        }
        AudioPlayer.limits()
        for (codec in com.vayunmathur.cast.protocol.VideoCodec.entries) {
            VideoDecoder.decoderName(codec) // may be null (no hardware codec); must not throw
        }
    }

}
