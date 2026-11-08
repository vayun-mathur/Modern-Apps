package com.vayunmathur.things.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.platform.DeviceController.LinkState
import com.vayunmathur.things.platform.Sex

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp), so the letterboxed expanded layout renders. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:things`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Home() {
        DynamicTheme(darkTheme = true) {
            HomePage(
                bottlePaired = true,
                bottleLink = LinkState.Connected,
                bottleConnectionState = "Connected",
                tempC = 22,
                tds = 85,
                batteryPct = 75,
                charging = false,
                volumePct = 60,
                lastUpdatedMillis = 1_788_277_018_000L,
                scalePaired = true,
                scaleLink = LinkState.Connected,
                scaleConnectionState = "Connected — step on scale",
                scaleUserSlot = 1,
                scaleSex = Sex.Male,
                scaleAge = "30",
                scaleHeight = "178",
                scaleAthlete = false,
                onScaleSexChange = {},
                onScaleAgeChange = {},
                onScaleHeightChange = {},
                onScaleAthleteChange = {},
                onForgetBottle = {},
                onForgetScale = {},
                onResetScale = {},
                onOpenDevices = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-devices", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Devices() {
        DynamicTheme(darkTheme = true) {
            DevicesPage(
                scanning = false,
                discoveredDevices = emptyList(),
                scaleScanning = false,
                scaleDevices = emptyList(),
                onScanClick = {},
                onDeviceClick = {},
                onScaleScanClick = {},
                onScaleDeviceClick = {},
                onNavigateBack = null,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-permissions-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4PermissionsExpanded() {
        DynamicTheme(darkTheme = true) {
            PermissionsPage(
                bluetooth = PermissionState.Needed,
                healthConnect = PermissionState.Blocked,
                onRequestBluetooth = {},
                onRequestHealthConnect = {},
                onResolveBluetooth = {},
                onResolveHealthConnect = {},
            )
        }
    }
    @PreviewTest
    @Preview(name = "3-home-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview3HomeExpanded() {
        DynamicTheme(darkTheme = true) {
            HomePage(
                bottlePaired = true,
                bottleLink = LinkState.Connected,
                bottleConnectionState = "Connected",
                tempC = 22,
                tds = 85,
                batteryPct = 75,
                charging = false,
                volumePct = 60,
                lastUpdatedMillis = 1_788_277_018_000L,
                scalePaired = true,
                scaleLink = LinkState.Connected,
                scaleConnectionState = "Connected — step on scale",
                scaleUserSlot = 1,
                scaleSex = Sex.Male,
                scaleAge = "30",
                scaleHeight = "178",
                scaleAthlete = false,
                onScaleSexChange = {},
                onScaleAgeChange = {},
                onScaleHeightChange = {},
                onScaleAthleteChange = {},
                onForgetBottle = {},
                onForgetScale = {},
                onResetScale = {},
                onOpenDevices = {},
            )
        }
    }
}
