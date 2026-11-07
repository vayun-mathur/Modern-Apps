package com.vayunmathur.games.kart.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.util.KartNative
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KartHostAndNativeTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun hostLifecycleWithPairingStore() = runBlocking {
        val pairing = KartPairingStore(ctx)
        val host = KartHost(ctx, publicBundle = { null }, unsealSecret = { null }, pairingStore = pairing)
        assertEquals("kart", host.lobby.value.gameId)
        assertTrue(host.lobby.value.slots.isEmpty())
        assertTrue(host.inputs.value.isEmpty())
        assertNull(host.host)
        assertNull(host.pairingCode)
        host.start()
        // Idempotent: a second start is a no-op, not a second lobby.
        host.start()
        assertNotNull(host.host)
        assertEquals("kart", host.lobby.value.gameId)
        host.stop()
    }

    @Test fun hostLifecycleWithLegacyPrefsFallback() = runBlocking {
        val host = KartHost(ctx, publicBundle = { byteArrayOf(1) }, unsealSecret = { byteArrayOf(2) })
        host.start()
        assertNotNull(host.host)
        // Give the IO accept loop a moment to bind and advertise before teardown.
        Thread.sleep(300)
        assertEquals("kart", host.lobby.value.gameId)
        host.stop()
        // A fresh host over the same prefs reuses the minted legacy host id path without error.
        val host2 = KartHost(ctx, publicBundle = { null }, unsealSecret = { null })
        host2.start()
        assertNotNull(host2.host)
        host2.stop()
    }

    @Test fun nativeBridgeCallsAreSafe() {
        // On host JVM tests the Rust engine may be present; on Robolectric it may not.
        // Either way every guarded call must return normally and standings must be JSON-shaped.
        KartNative.startRace(listOf("p1", "p2"))
        KartNative.onDriveInput(0, 1f, 0.5f, 0f)
        KartNative.setDrift(0, true)
        KartNative.useItem(0)
        KartNative.surfaceChanged(320, 240)
        val standings = KartNative.getStandingsJson()
        assertTrue(standings.startsWith("[") && standings.endsWith("]"), standings)
        KartNative.surfaceDestroyed()
        KartNative.onDestroy()
    }
}
