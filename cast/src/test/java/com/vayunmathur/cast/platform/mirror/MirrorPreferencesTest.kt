package com.vayunmathur.cast.platform.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MirrorPreferencesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `target round trips and sender id is stable`() = runTest {
        val device = CastDevice(id = "tv-9", friendlyName = "Den TV", host = "10.0.0.9", port = 4321)
        MirrorPreferences.setTarget(context, device)
        val read = MirrorPreferences.target(context)
        assertEquals("tv-9", read?.id)
        assertEquals("Den TV", read?.friendlyName)
        assertEquals("10.0.0.9", read?.host)
        assertEquals(4321, read?.port)

        val first = MirrorPreferences.senderId(context)
        val second = MirrorPreferences.senderId(context)
        assertTrue(first.isNotBlank())
        assertEquals(first, second)
    }

    @Test
    fun `device key round trips per receiver`() = runTest {
        assertNull(MirrorPreferences.deviceKey(context, "never-paired"))
        val key = ByteArray(32) { (it * 3).toByte() }
        MirrorPreferences.rememberDeviceKey(context, "tv-a", key)
        assertTrue(key.contentEquals(MirrorPreferences.deviceKey(context, "tv-a")))
        assertNull(MirrorPreferences.deviceKey(context, "tv-b"))
    }

    @Test
    fun `demoted codecs accumulate per receiver and clear`() = runTest {
        assertTrue(MirrorPreferences.demotedCodecs(context, "tv-d").isEmpty())
        MirrorPreferences.demoteCodec(context, "tv-d", VideoCodec.Av1)
        MirrorPreferences.demoteCodec(context, "tv-d", VideoCodec.Hevc)
        MirrorPreferences.demoteCodec(context, "tv-d", VideoCodec.Av1)
        assertEquals(setOf(VideoCodec.Av1, VideoCodec.Hevc), MirrorPreferences.demotedCodecs(context, "tv-d"))
        assertTrue(MirrorPreferences.demotedCodecs(context, "other").isEmpty())
        MirrorPreferences.clearDemotedCodecs(context, "tv-d")
        assertTrue(MirrorPreferences.demotedCodecs(context, "tv-d").isEmpty())
    }
}
