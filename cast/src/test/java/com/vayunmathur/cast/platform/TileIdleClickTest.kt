package com.vayunmathur.cast.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.platform.mirror.MirrorTileService
import com.vayunmathur.cast.protocol.MACAST_SERVICE_TYPE
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNsdManager

/**
 * The tile's idle click: resolve the stored TV over mDNS, connect (pairing
 * silently with the remembered key), and hand off toward consent.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileIdleClickTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nsd: NsdManager get() = context.getSystemService(NsdManager::class.java)
    private val shadow: ShadowNsdManager get() = Shadows.shadowOf(nsd)

    @Before fun reset() {
        ShadowNsdManager.reset()
        runBlocking { CastController.teardown() }
    }

    @After fun cleanup() {
        runBlocking { CastController.teardown() }
        ShadowNsdManager.reset()
    }

    @Test
    fun `idle click resolves stored tv and pairs silently`() = runBlocking {
        val key = ByteArray(32) { (it + 7).toByte() }
        val tv = FakeTv(codeRequired = false, rememberedKey = key)
        try {
            val stored = CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "192.0.2.1", port = 1)
            MirrorPreferences.setTarget(context, stored)
            MirrorPreferences.rememberDeviceKey(context, "tv-fake-1", key)

            val controller = Robolectric.buildService(MirrorTileService::class.java)
            val service = controller.create().get()
            service.onTileAdded()
            service.onStartListening()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            service.onClick()

            // Drive the mDNS answer the tile's browse is waiting for.
            val deadline = System.currentTimeMillis() + 10_000
            var answered = false
            while (System.currentTimeMillis() < deadline && !answered) {
                val listener = (shadow.getDiscoveryListeners(MACAST_SERVICE_TYPE) ?: emptyList())
                    .firstOrNull()
                if (listener != null) {
                    val found = NsdServiceInfo().apply {
                        serviceName = "Fake TV instance"
                        serviceType = MACAST_SERVICE_TYPE
                        setPort(tv.port)
                        setHost(InetAddress.getByName("127.0.0.1"))
                        setAttribute("fn", "Fake TV")
                        setAttribute("id", "tv-fake-1")
                    }
                    listener.onServiceFound(found)
                    (shadow.getResolveListeners(found) ?: emptyList())
                        .forEach { it.onServiceResolved(found) }
                    answered = true
                }
                delay(25)
            }
            assertTrue(answered, "the tile never browsed")

            // Silent pairing with the remembered key. The device the controller
            // holds carries the NSD-resolved address (127.0.0.1), not the stored
            // one (192.0.2.1) - proof the tile re-resolved before connecting.
            val pairDeadline = System.currentTimeMillis() + 10_000
            while (CastController.sessionState.value.phase != ClientPhase.Paired &&
                System.currentTimeMillis() < pairDeadline
            ) {
                delay(25)
            }
            assertEquals(ClientPhase.Paired, CastController.sessionState.value.phase)
            assertEquals("127.0.0.1", CastController.device.value?.host)

            // A second click with a live session is handled without disturbing it.
            service.onClick()
            delay(300)
            assertEquals(ClientPhase.Paired, CastController.sessionState.value.phase)
            Unit
        } finally {
            tv.close()
        }
    }
}
