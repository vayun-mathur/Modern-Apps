package com.vayunmathur.launcher.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.launcher.wear.domain.WatchApp
import com.vayunmathur.launcher.wear.domain.WearLauncherSections
import com.vayunmathur.launcher.wear.domain.WearSettingsTarget

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

private val fixtureApps = listOf(
    WatchApp(packageName = "com.vayunmathur.notes", className = ".MainActivity", label = "Notes"),
    WatchApp(packageName = "com.vayunmathur.maps", className = ".MainActivity", label = "Maps"),
    WatchApp(packageName = "com.vayunmathur.music", className = ".MainActivity", label = "Music"),
)

/**
 * Store listing images for `:launcher:wear` — every top-level screen/route (home, all-apps, settings).
 * `./gradlew :launcher:wear:metadata` copies renders into `metadata_data/photos/launcher-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1HomeRound() {
        RoundPreview {
            WearLauncherContent(
                sections = WearLauncherSections(favorites = fixtureApps.take(2), recents = fixtureApps.drop(2), companions = fixtureApps, all = fixtureApps),
                installedEmpty = false,
                onLaunch = {},
                onOpenAllApps = {},
                onOpenSettings = {},
                onRequestSync = { true },
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-all-apps-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2AllAppsRound() {
        RoundPreview { WearAllAppsContent(all = fixtureApps, onLaunch = {}, onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "3-settings-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3SettingsRound() {
        RoundPreview {
            WearSettingsContent(targets = listOf(WearSettingsTarget.DISPLAY, WearSettingsTarget.SOUND, WearSettingsTarget.CONNECTIVITY), onOpenTarget = {}, onBack = {})
        }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}
