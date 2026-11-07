package com.vayunmathur.cast.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.remotedisplay.MaRemoteDisplayProvider
import com.vayunmathur.cast.protocol.MACAST_SERVICE_TYPE
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNsdManager

/**
 * Drives the mDNS listener callbacks through Robolectric's NSD shadow: found,
 * resolved (full and degraded TXT), lost, and failed discovery.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryListenerTest {

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

    private fun serviceInfo(name: String, host: String = "192.168.1.50", port: Int = 41_000): NsdServiceInfo =
        NsdServiceInfo().apply {
            serviceName = name
            serviceType = MACAST_SERVICE_TYPE
            setPort(port)
            setHost(InetAddress.getByName(host))
        }

    private suspend fun listenerFor(manager: CastDiscoveryManager): Pair<NsdManager.DiscoveryListener, Job> {
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            .async { manager.discover().collect { } }
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val listeners = (shadow.getDiscoveryListeners(MACAST_SERVICE_TYPE) ?: emptyList())
            if (listeners.isNotEmpty()) return listeners.first() to job
            if (System.currentTimeMillis() > deadline) throw AssertionError("no discovery listener")
            delay(20)
        }
    }

    private fun resolveFound(
        listener: NsdManager.DiscoveryListener,
        found: NsdServiceInfo,
        resolved: NsdServiceInfo,
    ) {
        listener.onServiceFound(found)
        val resolvers = shadow.getResolveListeners(found) ?: emptyList()
        assertTrue(resolvers.isNotEmpty(), "resolve should have been requested")
        resolvers.forEach { it.onServiceResolved(resolved) }
    }

    @Test
    fun `found resolved lost and failed discovery drive the device list`() = runBlocking {
        val manager = CastDiscoveryManager(context)
        val (listener, job) = listenerFor(manager)
        listener.onDiscoveryStarted(MACAST_SERVICE_TYPE)
        assertEquals(false, manager.localNetworkBlocked.value)

        val found = serviceInfo("Living Room TV (instance)")
        val resolved = serviceInfo("Living Room TV (instance)").apply {
            setAttribute("fn", "Living Room TV")
            setAttribute("id", "tv-uuid-1")
            setAttribute("pv", "9")
        }
        resolveFound(listener, found, resolved)
        var devices = manager.devices.value
        assertEquals(1, devices.size)
        assertEquals("tv-uuid-1", devices[0].id)
        assertEquals("Living Room TV", devices[0].friendlyName)
        assertEquals("Living Room TV (instance)", devices[0].instanceName)
        assertEquals(9, devices[0].protocolVersion)
        assertEquals(41_000, devices[0].port)

        // A re-announcement replaces by id, it does not duplicate.
        val renamed = serviceInfo("Living Room TV (instance)").apply {
            setAttribute("fn", "Lounge TV")
            setAttribute("id", "tv-uuid-1")
            setAttribute("pv", "9")
        }
        resolveFound(listener, found, renamed)
        devices = manager.devices.value
        assertEquals(1, devices.size)
        assertEquals("Lounge TV", devices[0].friendlyName)

        // A record with no usable TXT falls back to the instance name and id.
        val bare = serviceInfo("Bedroom instance")
        resolveFound(listener, bare, serviceInfo("Bedroom instance", port = 9))
        assertEquals(2, manager.devices.value.size)
        val bedroom = manager.devices.value.first { it.instanceName == "Bedroom instance" }
        assertEquals("Bedroom instance", bedroom.id)
        assertEquals(0, bedroom.protocolVersion)

        // A resolve failure is logged, not fatal.
        val ghost = serviceInfo("Ghost")
        listener.onServiceFound(ghost)
        (shadow.getResolveListeners(ghost) ?: emptyList()).forEach {
            it.onResolveFailed(ghost, NsdManager.FAILURE_INTERNAL_ERROR)
        }
        assertEquals(2, manager.devices.value.size)

        // An unresolvable record (no port) never joins the list.
        val noPort = serviceInfo("NoPort").apply { setPort(0) }
        resolveFound(listener, noPort, serviceInfo("NoPort").apply { setPort(0) })
        assertEquals(2, manager.devices.value.size)

        listener.onServiceLost(serviceInfo("Bedroom instance"))
        assertEquals(listOf("tv-uuid-1"), manager.devices.value.map { it.id })
        listener.onServiceLost(NsdServiceInfo()) // no name: ignored
        listener.onDiscoveryStopped(MACAST_SERVICE_TYPE)
        listener.onStopDiscoveryFailed(MACAST_SERVICE_TYPE, NsdManager.FAILURE_INTERNAL_ERROR)

        manager.clear()
        assertTrue(manager.devices.value.isEmpty())

        listener.onStartDiscoveryFailed(MACAST_SERVICE_TYPE, NsdManager.FAILURE_ALREADY_ACTIVE)
        job.cancel()
        Unit
    }

    @Test
    fun `provider syncs routes from the shared discovery manager`() = runBlocking {
        val provider = MaRemoteDisplayProvider(context)
        provider.onDiscoveryModeChanged(
            com.android.media.remotedisplay.RemoteDisplayProvider.DISCOVERY_MODE_ACTIVE,
        )
        val manager = CastController.discovery(context)
        val (listener, _) = listenerFor(manager)
        listener.onDiscoveryStarted(MACAST_SERVICE_TYPE)
        resolveFound(
            listener,
            serviceInfo("TV One instance"),
            serviceInfo("TV One instance").apply {
                setAttribute("fn", "TV One")
                setAttribute("id", "route-1")
            },
        )
        // Provider collectors run on the main thread.
        repeat(50) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (manager.devices.value.isNotEmpty()) return@repeat
            delay(20)
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(200)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, manager.devices.value.size)

        // Rename, then vanish: syncRoutes updates and removes.
        resolveFound(
            listener,
            serviceInfo("TV One instance"),
            serviceInfo("TV One instance").apply {
                setAttribute("fn", "TV Uno")
                setAttribute("id", "route-1")
            },
        )
        manager.clear()
        Thread.sleep(100)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        provider.onDiscoveryModeChanged(
            com.android.media.remotedisplay.RemoteDisplayProvider.DISCOVERY_MODE_NONE,
        )
        provider.shutdown()
        Unit
    }
}
