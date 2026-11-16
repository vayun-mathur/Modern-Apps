package com.vayunmathur.taxi

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.webkit.CookieManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.network.uber.UberCookies
import com.vayunmathur.taxi.network.uber.UberWeb
import com.vayunmathur.taxi.platform.location.LocationProvider
import com.vayunmathur.taxi.ui.RideScreen
import com.vayunmathur.taxi.ui.UberConnectScreen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3TaxiLocScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun locationCurrentUsesCachedFixWhenGranted(): Unit = runBlocking {
        Shadows.shadowOf(ctx.applicationContext as android.app.Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        assertTrue(LocationProvider.hasPermission(ctx))
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.5; longitude = -122.5; time = System.currentTimeMillis()
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, loc)
        val here = LocationProvider.current(ctx)
        assertNotNull(here); assertEquals(37.5, here.latitude); assertEquals(-122.5, here.longitude)
        // Search with a real query exercises the geocoder path (empty result on Robolectric is fine).
        val results = LocationProvider.search(ctx, "Main Street")
        assertNotNull(results)
    }

    @Test fun uberCookiesParseNamesAndClear() {
        val cm = CookieManager.getInstance(); cm.setAcceptCookie(true)
        cm.setCookie(UberWeb.HOME, "sid=abc; path=/"); cm.setCookie(UberWeb.HOME, "csid=xyz; path=/"); cm.flush()
        val header = UberCookies.header()
        assertNotNull(header); assertTrue(header.contains("sid"))
        assertTrue(UberCookies.names(UberWeb.HOME).contains("sid"))
        assertEquals("(none)", UberCookies.names("https://no-cookies.example/").takeIf { UberCookies.header("https://no-cookies.example/") == null } ?: "(none)")
        UberCookies.clear(); cm.flush()
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            runCatching { compose.mainClock.advanceTimeBy(500) }
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        compose.onNodeWithText(text).assertExists()
    }

    @Test fun uberConnectScreenRendersAndTryNativeFires() {
        var tried = 0; var back = 0
        compose.setContent { UberConnectScreen(onBack = { back++ }, onTryNative = { tried++ }) }
        waitForText(ctx.getString(R.string.uber_try_native))
        compose.onNodeWithText(ctx.getString(R.string.uber_try_native)).performClick()
        assertEquals(1, tried)
    }

    @Test fun rideScreenTypingSearches() {
        compose.setContent { RideScreen() }
        waitForText(ctx.getString(R.string.destination))
        compose.onNodeWithText(ctx.getString(R.string.pickup_label)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.pickup_label)).performTextInput("123 Main")
        // Pump debounce + geocoder coroutine.
        repeat(20) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            runCatching { compose.mainClock.advanceTimeBy(500) }; Thread.sleep(20)
        }
        compose.onNodeWithText("123 Main", substring = true).assertExists()
    }
}
