package com.vayunmathur.madeveloper.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.madeveloper.domain.GitRepo
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** Store-listing previews for MA Developer. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Renders the listing screenshots from static state. */
class MetadataPreviews {
    @PreviewTest
    @Preview(name = "1-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun PreviewHome() {
        DynamicTheme(darkTheme = true) {
            HomeScreen(
                state = MaDevUiState(repos = listOf(GitRepo("Modern-Apps", "main", "abc12345", "Demo commit", "2026-11-16T00:00:00Z")), pairingCode = "ABC234"),
                onRefresh = {}, onOpenRepo = {}, onOpenTests = {}, onOpenCatalog = {}, onOpenPush = {},
            )
        }
    }
}
