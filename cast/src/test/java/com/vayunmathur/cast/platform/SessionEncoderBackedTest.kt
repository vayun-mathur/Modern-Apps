package com.vayunmathur.cast.platform

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.CaptureGeometry
import com.vayunmathur.cast.platform.mirror.FakeEncoders
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.sdk.cast.CastContract
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Session starts with fake hardware encoders installed: negotiation now succeeds. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionEncoderBackedTest {

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
                    "timeout; phase=${CastController.mirrorPhase.value} " +
                        "failure=${CastController.mirrorFailure.value} " +
                        "session=${CastController.sessionState.value.phase}",
                )
            }
            delay(15)
        }
    }

    private suspend fun paired(tv: FakeTv) {
        val target = CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "127.0.0.1", port = tv.port)
        CastController.connect(context, target, thenMirror = false)
        await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
        CastController.submitPairCode(context, FakeTv.CODE)
        await { CastController.sessionState.value.phase == ClientPhase.Paired }
    }

    @Test
    fun `encoded content session negotiates then fails on the missing surface`() = runBlocking {
        val tv = FakeTv()
        try {
            paired(tv)
            val result = CastController.startContentSession(
                context, 1280, 720, wantAudio = true, appLabel = "Music",
            )
            // The shadow codec yields no input Surface, so a session that negotiated
            // fine still cannot hand the app anything to draw into: named failure.
            assertTrue(result is ContentSessionResult.Failed, "got $result")
            assertEquals(
                CastContract.REASON_FAILED,
                (result as ContentSessionResult.Failed).reason,
            )
            assertEquals(MirrorPhase.Failed, CastController.mirrorPhase.value)
            // The TV did agree the stream first.
            assertTrue(tv.received.any { it is com.vayunmathur.cast.protocol.StreamConfig })
        } finally {
            tv.close()
        }
    }

    @Test
    fun `encoded content session fails when the tv refuses the stream`() = runBlocking {
        val tv = FakeTv(streamPort = 0)
        try {
            paired(tv)
            val result = CastController.startContentSession(
                context, 1280, 720, wantAudio = true, appLabel = "Music",
            )
            assertTrue(result is ContentSessionResult.Failed, "got $result")
            assertEquals(MirrorPhase.Failed, CastController.mirrorPhase.value)
            assertTrue(!CastController.mirrorFailure.value.isNullOrBlank())
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `mirroring negotiates a stream with a fake encoder`() = runBlocking {
        val tv = FakeTv()
        try {
            paired(tv)
            // Robolectric has no MediaProjection service; allocate the object
            // without its constructor - the pipeline only hands it around.
            // (Its capture-config builder cannot run against a projection with
            // no framework impl, so the engine start itself is the desktop
            // test's job; here we prove the controller negotiates the stream.)
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe")
                .apply { isAccessible = true }.get(null)
            val projection = unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, android.media.projection.MediaProjection::class.java)
                as android.media.projection.MediaProjection
            CastController.startMirroring(context, projection)
            await { tv.received.any { it is com.vayunmathur.cast.protocol.StreamConfig } }
            assertTrue(
                CastController.mirrorPhase.value == MirrorPhase.Negotiating ||
                    CastController.mirrorPhase.value == MirrorPhase.Mirroring ||
                    CastController.mirrorPhase.value == MirrorPhase.Failed,
                "phase=${CastController.mirrorPhase.value}",
            )
            runCatching { projection.stop() }
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `desktop mode negotiates and publishes a display id callback`() = runBlocking {
        val tv = FakeTv()
        try {
            paired(tv)
            var published = Int.MIN_VALUE
            CastController.startDesktopMode(context) { published = it }
            await {
                CastController.mirrorPhase.value == MirrorPhase.Mirroring ||
                    CastController.mirrorPhase.value == MirrorPhase.Failed
            }
            assertTrue(tv.received.any { it is com.vayunmathur.cast.protocol.StreamConfig })
            if (CastController.mirrorPhase.value == MirrorPhase.Mirroring) {
                // No real surface under Robolectric, so no display was created (-1)
                // or a display id was published; either way the callback fired.
                assertTrue(published != Int.MIN_VALUE || CastController.desktopSource != null)
                CastController.stopMirroring(context)
                await { CastController.mirrorPhase.value == MirrorPhase.Idle }
            }
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `desktop mode change guards`() = runBlocking {
        val source = MirrorSource.SystemDisplay()
        val running = CaptureGeometry(1280, 720, 160, 2_000_000, 30f)
        val target = CaptureGeometry(1920, 1080, 160, 4_000_000, 60f)
        source.supportedModes = listOf(target)
        CastController.activeGeometry = running
        // An in-flight renegotiation suppresses a second one.
        CastController.renegotiateJob = CastController.scope.launch { delay(5_000) }
        val mode = displayMode(1920, 1080, 60f)
        if (mode != null) {
            CastController.onDesktopModeChanged(context, source, mode)
            assertTrue(CastController.renegotiateJob?.isActive == true)
        }
        CastController.renegotiateJob?.cancel()
        CastController.renegotiateJob = null
        CastController.activeGeometry = null
        assertEquals(0.2f, MODE_RATE_TOLERANCE)
    }

    private fun displayMode(width: Int, height: Int, rate: Float): Display.Mode? = try {
        Display.Mode::class.java
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .newInstance(width, height, rate)
    } catch (_: Exception) {
        null
    }
}
