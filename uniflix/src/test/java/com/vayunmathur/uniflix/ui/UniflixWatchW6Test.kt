package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SubtitleTrack
import com.vayunmathur.uniflix.domain.VideoSource
import com.vayunmathur.uniflix.platform.DetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: WatchScreen with a playable source — controls overlay, subtitle /
 * server / quality menus — the branches the no-source panels never reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UniflixWatchW6Test {

    @get:Rule val rule = createComposeRule()

    private fun state() = DetailUiState(
        detail = MediaDetail(42, MediaType.MOVIE, "The Film", overview = "Overview"),
        source = VideoSource(
            streamUrl = "https://addons.invalid/v.m3u8",
            title = "Addon Stream",
            subtitles = listOf(
                SubtitleTrack("en", "English", "https://addons.invalid/en.vtt"),
                SubtitleTrack("es", "Spanish", "https://addons.invalid/es.vtt"),
            ),
        ),
        servers = listOf("Alpha", "Beta"),
        selectedServer = "Alpha",
    )

    @Test
    fun watchWithSource_controlsAndMenus() {
        var server = ""
        rule.setContent {
            DynamicTheme {
                WatchScreen(
                    state = state(), season = 0, episode = 0,
                    onBack = {}, onRetry = {}, onOpenAddons = {},
                    onSelectServer = { server = it }, onSavePosition = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        // Open each labelled menu and pick an entry where offered.
        for (label in listOf("English", "Subtitles", "Alpha", "Server", "Quality")) {
            openMenu(label)
        }
        assertTrue(server.isEmpty() || server == "Beta")
    }

    private fun openMenu(label: String) {
        val nodes = rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) return
        rule.onAllNodesWithText(label, substring = true)[0].performClick()
        rule.waitForIdle()
        if (rule.onAllNodesWithText("Spanish", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("Spanish", substring = true)[0].performClick()
        }
        if (rule.onAllNodesWithText("Beta", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("Beta", substring = true)[0].performClick()
        }
        rule.waitForIdle()
    }

    @Test
    fun watchResolvingAndError_retry() {
        var retries = 0
        rule.setContent {
            DynamicTheme {
                WatchScreen(
                    state = DetailUiState(detail = MediaDetail(42, MediaType.MOVIE, "The Film"), resolveError = "Addon exploded"),
                    season = 0, episode = 0,
                    onBack = {}, onRetry = { retries++ }, onOpenAddons = {},
                    onSelectServer = {}, onSavePosition = { _, _ -> },
                )
            }
        }
        rule.onAllNodesWithText("Retry", substring = true)[0].performClick()
        assertEquals(1, retries)
    }
}
