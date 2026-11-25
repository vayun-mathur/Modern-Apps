package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.setupwizard.platform.SetupUiState
import java.util.Locale

/** Phone-shaped, roughly 1080x2340 at xxhdpi. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private val fixtureState = SetupUiState(
    isPrimaryUser = true,
    language = "English (United States)",
    locationEnabled = true,
    wifiScanningEnabled = true,
    bootloaderAckSeconds = 0,
)

/**
 * Store listing images for `:setupwizard` in the `phone` bucket — every route with
 * UI of its own (welcome, OEM-unlock warning, location, migration, gestures, finish).
 *
 * Exceptions (no UI exists to capture): `Route.Wifi` and `Route.Security` are
 * `HandoffScreen` steps that draw nothing and launch the system's Wi-Fi/lock
 * activities, so there is no setupwizard screen for them — capturing one would
 * mean faking a system screen.
 * `./gradlew :setupwizard:metadata` copies renders into `metadata_data/photos/setupwizard/phone/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-welcome", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Welcome() {
        PreviewTheme {
            WelcomeScreen(state = fixtureState, languages = { listOf(Locale.US) }, onLanguageSelected = {}, onAccessibility = {}, onEmergencyCall = null, onNext = {})
        }
    }

    @PreviewTest
    @Preview(name = "2-oem-unlock", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2OemUnlock() {
        PreviewTheme {
            OemUnlockScreen(state = fixtureState.copy(bootloaderUnlocked = true), onStartAckTimer = {}, onRebootToBootloader = {}, onContinue = {})
        }
    }

    @PreviewTest
    @Preview(name = "3-location", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Location() {
        PreviewTheme {
            LocationScreen(state = fixtureState, onLocationEnabled = {}, onWifiScanningEnabled = {}, onNext = {})
        }
    }

    @PreviewTest
    @Preview(name = "4-migration", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Migration() {
        PreviewTheme { MigrationScreen(onRestore = {}, onSkip = {}) }
    }

    @PreviewTest
    @Preview(name = "5-gestures", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Gestures() {
        PreviewTheme { GesturesScreen(onTryIt = {}, onSkip = {}) }
    }

    @PreviewTest
    @Preview(name = "6-finish", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6Finish() {
        PreviewTheme {
            FinishScreen(state = fixtureState.copy(oemUnlockingEnabled = true), onFinish = {})
        }
    }
}

@Composable
private fun PreviewTheme(content: @Composable () -> Unit) {
    DynamicTheme { content() }
}
