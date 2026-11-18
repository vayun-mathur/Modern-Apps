package com.vayunmathur.camera.platform

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.WifiQrConfig
import com.vayunmathur.camera.domain.WifiSecurity
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** WifiConnector outcomes under Robolectric's WiFi shadows. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiConnectorExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun wepFallsBackToSettings() {
        var result: WifiConnectResult? = null
        WifiConnector(context).connect(
            WifiQrConfig(ssid = "Legacy", security = WifiSecurity.WEP, password = "abcde", hidden = false),
        ) { result = it }
        // WEP is unsupported for suggestions: settings fallback (or FAILED if
        // the harness has no WiFi settings activity) — never SUGGESTED.
        assertTrue(result == WifiConnectResult.FELL_BACK_TO_SETTINGS || result == WifiConnectResult.FAILED)
    }

    @Test fun openAndWpaSuggestionsReportAnOutcome() {
        for (config in listOf(
            WifiQrConfig(ssid = "OpenNet", security = WifiSecurity.OPEN, password = null, hidden = false),
            WifiQrConfig(ssid = "Home", security = WifiSecurity.WPA, password = "hunter2hunter", hidden = true),
        )) {
            var result: WifiConnectResult? = null
            WifiConnector(context).connect(config) { result = it }
            assertTrue(result != null, "callback must fire synchronously for $config")
        }
    }
}
