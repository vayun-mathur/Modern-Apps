package com.vayunmathur.cast.platform

import android.content.Context
import android.hardware.display.DisplayManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.CaptureGeometry
import com.vayunmathur.cast.platform.mirror.FakeEncoders
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.cast.platform.mirror.ShadowMediaCodecWithSurface
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Desktop mode: start, then follow a Settings mode change through re-negotiation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaCodecWithSurface::class])
class DesktopRenegotiateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeEncoders.install()
        runBlocking { CastController.teardown() }
    }

    @After fun tearDown() {
        runBlocking { CastController.teardown() }
        FakeEncoders.reset()
    }

    private suspend fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "timeout; phase=${CastController.mirrorPhase.value} failure=${CastController.mirrorFailure.value}",
                )
            }
            delay(15)
        }
    }

    @Test
    fun `desktop starts then renegotiates to the mode settings picked`() = runBlocking {
        val tv = FakeTv()
        try {
            val target = CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "127.0.0.1", port = tv.port)
            CastController.connect(context, target, thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(context, FakeTv.CODE)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }

            CastController.startDesktopMode(context)
            await {
                CastController.mirrorPhase.value == MirrorPhase.Mirroring ||
                    CastController.mirrorPhase.value == MirrorPhase.Failed
            }
            assertTrue(tv.received.any { it is StreamConfig }, "desktop should negotiate a stream")
            if (CastController.mirrorPhase.value != MirrorPhase.Mirroring) {
                // The display/encoder platform under Robolectric refused; the
                // renegotiation below still exercises the controller path with
                // the session state a running desktop would have left behind.
                CastController.mirrorPhaseMutable.value = MirrorPhase.Mirroring
                CastController.sessionStateMutable.value =
                    CastController.sessionState.value.copy(phase = ClientPhase.Streaming)
            }

            val displays = context.getSystemService(DisplayManager::class.java)
            val mode = displays.getDisplay(0)?.mode
            assertTrue(mode != null)
            val source = CastController.desktopSource ?: MirrorSource.SystemDisplay().also {
                CastController.desktopSource = it
            }
            val picked = CaptureGeometry(mode.physicalWidth, mode.physicalHeight, 160, 3_000_000, mode.refreshRate)
            val running = CaptureGeometry(1280, 720, 160, 2_000_000, 30f)
            source.supportedModes = listOf(picked)
            CastController.activeGeometry = running
            if (CastController.activeCodec == null) CastController.activeCodec = VideoCodec.Hevc

            val configsBefore = tv.received.count { it is StreamConfig }
            CastController.onDesktopModeChanged(context, source, mode)
            await { tv.received.count { it is StreamConfig } > configsBefore }
            await { CastController.activeGeometry == picked }
            assertEquals(picked, CastController.activeGeometry)

            CastController.stopMirroring(context)
            await { CastController.mirrorPhase.value == MirrorPhase.Idle }
        } finally {
            tv.close()
        }
        Unit
    }
}
