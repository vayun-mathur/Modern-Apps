package com.vayunmathur.findfamily.ui

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLocationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup overflow menu interactions and [Platform] clipboard/location helpers. */
@RunWith(AndroidJUnit4::class)
class BackupAndPlatformRealTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun backupMenuOpensAndItemsDismissIt() {
        var expanded = true
        rule.setContent {
            DynamicTheme {
                var state by remember { mutableStateOf(expanded) }
                BackupMenuButton(
                    dbPassphrase = "passphrase",
                    menuExpanded = state,
                    onMenuExpandedChange = { state = it; expanded = it },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Back up", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Restore", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Restore", substring = true).performClick()
        rule.waitForIdle()
    }

    @Test fun backupMenuClosedShowsOnlyOverflowIcon() {
        rule.setContent {
            DynamicTheme {
                BackupMenuButton(dbPassphrase = "pw", menuExpanded = false, onMenuExpandedChange = {})
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Back up", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun copyWritesToClipboard() {
        Platform(ctx).copy("findfamily-id-123")
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("findfamily-id-123", clipboard.primaryClip?.getItemAt(0)?.text)
    }

    @Test fun lastKnownLocationRequiresPermission() {
        assertNull(Platform(ctx).lastKnownDeviceLocation())
    }

    @Test fun lastKnownLocationPrefersNewestThenGps() {
        val shadowApp = Shadows.shadowOf(ctx as android.app.Application)
        shadowApp.grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(locationManager) as ShadowLocationManager
        val network = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 10.0; longitude = 20.0; time = 2_000L
        }
        val gps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 30.0; longitude = 40.0; time = 1_000L
        }
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, network)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        // Newest fix wins, even from the network provider.
        assertEquals(GeoPoint(20.0, 10.0), Platform(ctx).lastKnownDeviceLocation())
        val newerGps = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 50.0; longitude = 60.0; time = 3_000L
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, newerGps)
        assertEquals(GeoPoint(60.0, 50.0), Platform(ctx).lastKnownDeviceLocation())
    }
}
