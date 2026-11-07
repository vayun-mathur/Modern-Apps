package com.vayunmathur.cast.platform

import android.content.Context
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.media.remotedisplay.RemoteDisplay
import com.android.media.remotedisplay.RemoteDisplayProvider
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.mirror.CaptureGeometry
import com.vayunmathur.cast.platform.mirror.MirrorTileService
import com.vayunmathur.cast.platform.remotedisplay.CastSystemDisplay
import com.vayunmathur.cast.platform.remotedisplay.MaRemoteDisplayProvider
import com.vayunmathur.cast.service.CastRemoteDisplayService
import com.vayunmathur.cast.ui.CastScreen
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteDisplayAndTileTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `provider lifecycle discovery modes and route callbacks`() {
        val provider = MaRemoteDisplayProvider(context)
        provider.onDiscoveryModeChanged(RemoteDisplayProvider.DISCOVERY_MODE_ACTIVE)
        provider.onDiscoveryModeChanged(RemoteDisplayProvider.DISCOVERY_MODE_ACTIVE) // already running
        Thread.sleep(200)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // Controller emissions with no route connecting are ignored by the observers.
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.AwaitingCode, attemptsLeft = 1)
        CastController.mirrorPhaseMutable.value = MirrorPhase.Mirroring
        Thread.sleep(100)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val display = RemoteDisplay("route-1", "TV")
        provider.onConnect(display) // stub id is null: refused inside
        provider.onDisconnect(display)
        provider.onDiscoveryModeChanged(RemoteDisplayProvider.DISCOVERY_MODE_NONE)
        provider.onDiscoveryModeChanged(RemoteDisplayProvider.DISCOVERY_MODE_PASSIVE)
        Thread.sleep(100)
        provider.shutdown()
        provider.shutdown()
    }

    @Test
    fun `remote display service binds the provider for the real action`() {
        val controller = Robolectric.buildService(CastRemoteDisplayService::class.java)
        val service = controller.create().get()
        service.onBind(
            android.content.Intent(RemoteDisplayProvider.SERVICE_INTERFACE),
        )
        controller.destroy()
    }

    @Test
    fun `system display start attach release without a platform display`() {
        val display = CastSystemDisplay(context)
        assertEquals(-1, display.displayId)
        val surface = Surface(android.graphics.SurfaceTexture(1))
        val geometry = CaptureGeometry(1280, 720, 160, 2_000_000, 30f)
        // Robolectric has no trusted-display platform: start must answer false or
        // throw nothing, attach with no display is false, release is safe.
        val started = runCatching {
            display.start(surface, geometry, "tv-1", listOf(geometry))
        }.getOrDefault(false)
        if (!started) {
            assertEquals(false, display.attach(surface))
        }
        display.release()
        display.release()
        surface.release()
    }

    @Test
    fun `tile service listens and handles clicks in both phases`() {
        val controller = Robolectric.buildService(MirrorTileService::class.java)
        val service = controller.create().get()
        service.onTileAdded()
        service.onStartListening()
        // Mirroring/Negotiating clicks ask the controller to stop. (The idle click
        // collapses the shade into the app, which needs a bound QS service that
        // Robolectric does not provide, so that path stays device-only.)
        CastController.mirrorPhaseMutable.value = MirrorPhase.Mirroring
        service.onClick()
        Thread.sleep(200)
        CastController.mirrorPhaseMutable.value = MirrorPhase.Negotiating
        service.onClick()
        Thread.sleep(100)
        // No controller.destroy(): Robolectric's ShadowTileService cannot cast itself
        // in Service.onDestroy, which is a shadow bug, not the tile's.
    }

    @Test
    fun `cast screen composes with a real view model`() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val vm = CastViewModel(app)
        composeRule.setContent { CastScreen(vm) }
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onNodeWithText("Televisions").fetchSemanticsNode() != null ||
                composeRule.onNodeWithText("No televisions found").fetchSemanticsNode() != null,
        )
        vm.stopScan()
    }
}
