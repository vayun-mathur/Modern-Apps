package com.vayunmathur.appstore.wear.ui

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
import com.vayunmathur.appstore.wear.data.WearRow
import com.vayunmathur.appstore.wear.domain.WatchAppPhase
import com.vayunmathur.appstore.wear.domain.WearCatalogApp

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/**
 * Store listing images for `:appstore:wear` — the module's only top-level screen
 * (one catalog/install list by design; see WearMainActivity).
 * `./gradlew :appstore:wear:metadata` copies renders into `metadata_data/photos/appstore-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-store-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1StoreRound() {
        RoundPreview {
            WearStoreContent(
                rows = listOf(
                    WearRow(app = WearCatalogApp(packageName = "com.vayunmathur.notes", name = "Notes", versionName = "1.0"), phase = WatchAppPhase.READY_TO_INSTALL, installRequested = true),
                    WearRow(app = WearCatalogApp(packageName = "com.vayunmathur.maps", name = "Maps", versionName = "1.0"), phase = WatchAppPhase.INSTALLED),
                    WearRow(app = WearCatalogApp(packageName = "com.vayunmathur.music", name = "Music", versionName = "1.0"), phase = WatchAppPhase.LISTED),
                ),
                onInstall = {},
            )
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
