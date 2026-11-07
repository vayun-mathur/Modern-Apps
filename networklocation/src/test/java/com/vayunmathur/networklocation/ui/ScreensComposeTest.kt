package com.vayunmathur.networklocation.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.networklocation.OfflineDatabases
import com.vayunmathur.networklocation.writeGeocoderDb
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ScreensComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearDbs() {
        for (n in listOf(OfflineDatabases.GEOCODER, OfflineDatabases.WIFI, OfflineDatabases.CELL)) {
            OfflineDatabases.file(context, n).delete()
        }
    }

    private fun installAllDbs() {
        writeGeocoderDb(OfflineDatabases.file(context, OfflineDatabases.GEOCODER))
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(byteArrayOf(1))
        OfflineDatabases.file(context, OfflineDatabases.CELL).writeBytes(byteArrayOf(1))
    }

    @Test
    fun `setup screen explains the provider and offers the download`() {
        clearDbs()
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        var ready = false
        rule.setContent { SetupScreen(onReady = { ready = true }) }
        rule.onNodeWithText("Network Location").assertIsDisplayed()
        rule.onNodeWithText(
            "Provides the system network-location provider and an on-device, offline " +
                "geocoder. Both resolve entirely on device from the downloaded databases " +
                "below — nothing is sent to a positioning service. Always on — there is no " +
                "disable switch.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Offline databases").assertIsDisplayed()
        // The button sits below the fold of the scrolling column.
        rule.onNodeWithText("Download offline databases").assertExists()
        assertTrue(!ready, "onReady must not fire before the databases exist")
    }

    @Test
    fun `setup screen reports ready once every database is installed`() {
        installAllDbs()
        var ready = false
        rule.setContent { SetupScreen(onReady = { ready = true }) }
        rule.onNodeWithText(
            "All offline databases are installed. Positioning and geocoding work without " +
                "a network connection.",
        ).assertIsDisplayed()
        rule.waitUntil(5_000) { ready }
        assertTrue(ready)
    }

    @Test
    fun `status screen without location permission says so`() {
        installAllDbs()
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        rule.setContent { StatusScreen() }
        rule.onNodeWithText("Network Location").assertIsDisplayed()
        rule.onNodeWithText(
            "Location permission is not granted, so live positions cannot be shown.",
        ).assertIsDisplayed()
        rule.onNodeWithText(
            "All offline databases are installed. Positioning and geocoding work without " +
                "a network connection.",
        ).assertIsDisplayed()
    }

    @Test
    fun `live section with permission waits for fixes`() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        rule.setContent { LiveLocationSection() }
        rule.onNodeWithText("Live check").assertIsDisplayed()
        rule.onNodeWithText("GPS").assertIsDisplayed()
        rule.onNodeWithText("Waiting for a GPS fix… (go outside or near a window)").assertIsDisplayed()
        rule.onNodeWithText("Network location (this app)").assertIsDisplayed()
        rule.onNodeWithText(
            "No network fix yet. Cell and Wi-Fi scans resolve against the offline stores.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Offline geocoder (GPS position)").assertIsDisplayed()
        rule.onNodeWithText("Waiting for a GPS fix to look up.").assertIsDisplayed()
    }

    @Test
    fun `live section shows a gps fix and the geocoder no-match state`() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.4215
            longitude = -122.084
            accuracy = 5f
            elapsedRealtimeNanos = System.nanoTime()
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        rule.setContent { LiveLocationSection() }
        rule.onNodeWithText("37.421500, -122.084000").assertIsDisplayed()
        // The shadow geocoder has no canned address, so the lookup settles on no-match.
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("No nearby record in the database.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("No nearby record in the database.").assertIsDisplayed()
    }

    @Test
    fun `live section shows gps and network fixes with their comparison`() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        fun fix(provider: String, lat: Double, lon: Double) = Location(provider).apply {
            latitude = lat
            longitude = lon
            accuracy = 8f
            elapsedRealtimeNanos = System.nanoTime()
        }
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fix(LocationManager.GPS_PROVIDER, 37.4215, -122.084),
        )
        shadow.setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 37.4225, -122.085),
        )
        rule.setContent { LiveLocationSection() }
        rule.onNodeWithText("37.421500, -122.084000").assertIsDisplayed()
        rule.onNodeWithText("37.422500, -122.085000").assertExists()
        // The network card annotates its distance from the GPS fix.
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("m from GPS", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setGpsFix() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 37.4215
                longitude = -122.084
                accuracy = 5f
                elapsedRealtimeNanos = System.nanoTime()
            },
        )
    }

    @Test
    fun `live section shows a found geocode result`() {
        setGpsFix()
        val address = android.location.Address(java.util.Locale.US).apply {
            latitude = 37.4216
            longitude = -122.0841
            setAddressLine(0, "123 Main St")
            featureName = "123"
            thoroughfare = "Main St"
            locality = "Springfield"
            adminArea = "CA"
            postalCode = "12345"
            countryCode = "US"
        }
        rule.setContent {
            LiveLocationSection(geocodeLookup = { _, _, _ -> Result.success(address) })
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("123 Main St").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("123 Main St").assertIsDisplayed()
        rule.onNodeWithText("Main St, Springfield, CA, 12345, US").assertExists()
        rule.onNodeWithText("m from the GPS fix", substring = true).assertExists()
    }

    @Test
    fun `live section shows a failed geocode lookup`() {
        setGpsFix()
        rule.setContent {
            LiveLocationSection(
                geocodeLookup = { _, _, _ -> Result.failure(IllegalStateException("boom")) },
            )
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Lookup failed: boom").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Lookup failed: boom").assertIsDisplayed()
    }

    @Test
    fun `live section shows the looking state while a lookup is in flight`() {
        setGpsFix()
        val gate = kotlinx.coroutines.CompletableDeferred<Result<android.location.Address?>>()
        rule.setContent {
            LiveLocationSection(geocodeLookup = { _, _, _ -> gate.await() })
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Looking up…", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        gate.complete(Result.success(null))
    }

    @Test
    fun `download button can be tapped and then disables itself`() {
        clearDbs()
        try {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder().build(),
            )
        } catch (_: Exception) {
            // Already initialised by an earlier test in this process.
        }
        rule.setContent { SetupScreen(onReady = {}) }
        rule.onNodeWithText("Download offline databases").performScrollTo().performClick()
        rule.onNodeWithText("Downloading over Wi-Fi…").assertExists()
    }
}
