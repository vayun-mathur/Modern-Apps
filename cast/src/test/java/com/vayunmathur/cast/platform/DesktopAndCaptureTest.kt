package com.vayunmathur.cast.platform

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.CaptureGeometry
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.cast.platform.mirror.ScreenCapture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesktopAndCaptureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `desktop mode changed guards without a running geometry`() {
        val source = MirrorSource.SystemDisplay()
        val displays = context.getSystemService(DisplayManager::class.java)
        val mode = displays.getDisplay(0)?.mode
        assertNotNull(mode)
        // No active geometry: ignored.
        CastController.onDesktopModeChanged(context, source, mode)
        // Geometry identical to the only supported mode: also not a change.
        CastController.activeGeometry = CaptureGeometry(mode.physicalWidth, mode.physicalHeight, 160, 1_000_000, mode.refreshRate)
        source.supportedModes = listOf(CastController.activeGeometry!!)
        CastController.onDesktopModeChanged(context, source, mode)
        CastController.activeGeometry = null
        source.supportedModes = emptyList()
    }

    @Test
    fun `watch display registers and unregisters`() {
        val source = MirrorSource.SystemDisplay()
        CastController.watchDisplay(context, source)
        CastController.stopWatchingDisplay()
        CastController.stopWatchingDisplay()
    }

    @Test
    fun `desktop session refuses when the phone has no encoder`() = runBlocking {
        val tv = FakeTv()
        try {
            val target = CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "127.0.0.1", port = tv.port)
            CastController.connect(context, target, thenMirror = false)
            val deadline = System.currentTimeMillis() + 8_000
            while (CastController.sessionState.value.phase != ClientPhase.AwaitingCode) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("no awaiting")
                delay(15)
            }
            CastController.submitPairCode(context, FakeTv.CODE)
            while (CastController.sessionState.value.phase != ClientPhase.Paired) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("no pair")
                delay(15)
            }
            var displayId = Int.MIN_VALUE
            CastController.startDesktopMode(context) { displayId = it }
            while (CastController.mirrorPhase.value != MirrorPhase.Failed) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("no failure; phase=${CastController.mirrorPhase.value}")
                delay(15)
            }
            assertTrue(!CastController.mirrorFailure.value.isNullOrBlank())
            assertEquals(Int.MIN_VALUE, displayId)
        } finally {
            tv.close()
        }
    }

    @Test
    fun `screen capture start answers false when the platform refuses`() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(-1, Intent()) }.getOrNull()
            ?: return // platform has no projection service at all in this environment
        val capture = ScreenCapture(projection)
        val surface = Surface(SurfaceTexture(2))
        val started = capture.start(surface, CaptureGeometry(640, 480, 160, 1_000_000, 30f))
        assertEquals(false, started)
        capture.release()
        capture.release()
        surface.release()
        runCatching { projection.stop() }
    }
}
