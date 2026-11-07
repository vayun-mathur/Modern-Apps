package com.vayunmathur.cast.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.media.remotedisplay.RemoteDisplay
import com.android.media.remotedisplay.RemoteDisplayProvider
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.discovery.CastDiscoveryManager
import com.vayunmathur.cast.platform.mirror.FakeEncoders
import com.vayunmathur.cast.platform.mirror.ShadowMediaCodecSynthesizing
import com.vayunmathur.cast.platform.remotedisplay.MaRemoteDisplayProvider
import com.vayunmathur.cast.protocol.MACAST_SERVICE_TYPE
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNsdManager

/** A RemoteDisplay whose accessors actually store, unlike the compile-only stub. */
private class TestDisplay(id: String, name: String) : RemoteDisplay(id, name) {
    private var idValue = id
    private var nameValue = name
    var statusValue = RemoteDisplay.STATUS_AVAILABLE
    var presentationValue = -1
    var descriptionValue: String? = null

    override fun getId(): String = idValue
    override fun getName(): String = nameValue
    override fun setName(name: String) {
        nameValue = name
    }

    override fun getDescription(): String? = descriptionValue
    override fun setDescription(description: String) {
        descriptionValue = description
    }

    override fun getStatus(): Int = statusValue
    override fun setStatus(status: Int) {
        statusValue = status
    }

    override fun getPresentationDisplayId(): Int = presentationValue
    override fun setPresentationDisplayId(displayId: Int) {
        presentationValue = displayId
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowMediaCodecSynthesizing::class])
class ProviderRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nsd: NsdManager get() = context.getSystemService(NsdManager::class.java)
    private val shadow: ShadowNsdManager get() = Shadows.shadowOf(nsd)

    @Before fun setUp() {
        ShadowNsdManager.reset()
        FakeEncoders.install()
        runBlocking { CastController.teardown() }
    }

    @After fun tearDown() {
        runBlocking { CastController.teardown() }
        FakeEncoders.reset()
        ShadowNsdManager.reset()
    }

    private fun pump() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(40)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private suspend fun driveDiscovery(manager: CastDiscoveryManager, tv: FakeTv): Job {
        val job = CoroutineScope(Dispatchers.IO).async { manager.discover().collect { } }
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val listener = (shadow.getDiscoveryListeners(MACAST_SERVICE_TYPE) ?: emptyList())
                .firstOrNull()
            if (listener != null) {
                listener.onDiscoveryStarted(MACAST_SERVICE_TYPE)
                val found = NsdServiceInfo().apply {
                    serviceName = "Fake TV instance"
                    serviceType = MACAST_SERVICE_TYPE
                    setPort(tv.port)
                    setHost(InetAddress.getByName("127.0.0.1"))
                    setAttribute("fn", "Fake TV")
                    setAttribute("id", "route-1")
                }
                listener.onServiceFound(found)
                (shadow.getResolveListeners(found) ?: emptyList())
                    .forEach { it.onServiceResolved(found) }
                return job
            }
            if (System.currentTimeMillis() > deadline) throw AssertionError("no listener")
            delay(20)
        }
    }

    @Test
    fun `route is given up when pairing dies under the prompt`() = runBlocking {
        val tv = FakeTv()
        var browseJob: Job? = null
        try {
            val provider = MaRemoteDisplayProvider(context)
            provider.onDiscoveryModeChanged(RemoteDisplayProvider.DISCOVERY_MODE_ACTIVE)
            val manager = CastController.discovery(context)
            browseJob = driveDiscovery(manager, tv)
            repeat(60) {
                pump()
                if (manager.devices.value.any { it.id == "route-1" }) return@repeat
            }
            val display = TestDisplay("route-1", "Fake TV")
            provider.onConnect(display)
            val awaitDeadline = System.currentTimeMillis() + 8_000
            while (CastController.sessionState.value.phase != ClientPhase.AwaitingCode &&
                System.currentTimeMillis() < awaitDeadline
            ) {
                pump()
                delay(20)
            }
            assertEquals(ClientPhase.AwaitingCode, CastController.sessionState.value.phase)
            repeat(10) { pump() } // the prompt path runs (pairPromptId set)
            // The session dies under the prompt: the route is given up.
            CastController.teardown()
            repeat(20) { pump() }
            assertEquals(ClientPhase.Idle, CastController.sessionState.value.phase)
            provider.shutdown()
            Unit
        } finally {
            browseJob?.cancel()
            tv.close()
        }
    }

    @Test
    fun `route connect pairs through the prompt and disconnects`() = runBlocking {
        val tv = FakeTv()
        var browseJob: Job? = null
        try {
            val provider = MaRemoteDisplayProvider(context)
            provider.onDiscoveryModeChanged(RemoteDisplayProvider.DISCOVERY_MODE_ACTIVE)
            val manager = CastController.discovery(context)
            browseJob = driveDiscovery(manager, tv)
            repeat(60) {
                pump()
                if (manager.devices.value.any { it.id == "route-1" }) return@repeat
            }
            assertTrue(manager.devices.value.any { it.id == "route-1" })

            // Unknown route: refused in place.
            val unknown = TestDisplay("no-such-route", "Nope")
            provider.onConnect(unknown)
            assertEquals(RemoteDisplay.STATUS_NOT_AVAILABLE, unknown.statusValue)

            val display = TestDisplay("route-1", "Fake TV")
            provider.onConnect(display)
            assertEquals(RemoteDisplay.STATUS_CONNECTING, display.statusValue)

            // The session asks for a code; the provider's prompt path runs, then
            // submitting the code pairs and (paired phase) starts desktop mode.
            val awaitDeadline = System.currentTimeMillis() + 8_000
            while (CastController.sessionState.value.phase != ClientPhase.AwaitingCode &&
                System.currentTimeMillis() < awaitDeadline
            ) {
                pump()
                delay(20)
            }
            assertEquals(ClientPhase.AwaitingCode, CastController.sessionState.value.phase)
            pump()
            CastController.submitPairCode(context, FakeTv.CODE)
            val pairDeadline = System.currentTimeMillis() + 8_000
            while (CastController.sessionState.value.phase != ClientPhase.Paired &&
                CastController.sessionState.value.phase != ClientPhase.Streaming &&
                System.currentTimeMillis() < pairDeadline
            ) {
                pump()
                delay(20)
            }
            assertTrue(
                CastController.sessionState.value.phase == ClientPhase.Paired ||
                    CastController.sessionState.value.phase == ClientPhase.Streaming,
                "phase=${CastController.sessionState.value.phase}",
            )
            repeat(20) { pump() }

            provider.onDisconnect(display)
            assertEquals(RemoteDisplay.STATUS_AVAILABLE, display.statusValue)
            assertEquals(-1, display.presentationValue)
            provider.shutdown()
            Unit
        } finally {
            browseJob?.cancel()
            tv.close()
        }
    }
}
