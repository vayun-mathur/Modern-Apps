package com.vayunmathur.findfamily.util

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.Shadows
import android.Manifest
import android.location.Location
import android.location.LocationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric tests for [Platform] location/clipboard helpers and [UwbSessionManager] availability. */
@RunWith(AndroidJUnit4::class)
class PlatformAndUwbStateTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun lastKnownNullWithoutPermission() {
        assertNull(Platform(ctx).lastKnownDeviceLocation())
    }
    @Test fun lastKnownReturnsNewestGpsFix() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(lm)
        val gps = Location(LocationManager.GPS_PROVIDER).apply { latitude = 10.0; longitude = 20.0; time = 2000L }
        val net = Location(LocationManager.NETWORK_PROVIDER).apply { latitude = 30.0; longitude = 40.0; time = 1000L }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, net)
        val p = Platform(ctx).lastKnownDeviceLocation()
        assertEquals(20.0, p?.longitude); assertEquals(10.0, p?.latitude)
    }
    @Test fun copyWritesClipboard() {
        Platform(ctx).copy("hello-link")
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        assertEquals("hello-link", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }
    @Test fun uwbUnavailableAndStopResets() {
        assertFalse(UwbSessionManager.isAvailable(ctx))
        UwbSessionManager.stop()
        UwbSessionManager.startAsInitiator(99L)
        // Robolectric SDK is <36 (BAKLAVA), so the gate reports Unsupported - the real fail-closed path
        assertTrue(UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Unsupported)
        UwbSessionManager.stop()
        assertNull(UwbSessionManager.peerUserId.value)
        val fix = com.vayunmathur.findfamily.data.LocationValue(userid = 1L,
            coord = com.vayunmathur.findfamily.data.Coord(1.0, 2.0), speed = 0f, acc = 1f,
            timestamp = kotlin.time.Instant.fromEpochMilliseconds(1L), battery = 1f)
        assertEquals("pid", fix.withPairId("pid").pairId); assertNull(fix.withPairId(null).pairId)
        assertTrue(true)
    }
}
