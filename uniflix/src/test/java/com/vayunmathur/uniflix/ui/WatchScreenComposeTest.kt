package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** WatchScreen state panels (no playable source, so no real player is built). */
@RunWith(RobolectricTestRunner::class)
class WatchScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun watch(state: DetailUiState, onBack: () -> Unit = {}, onRetry: () -> Unit = {}, onOpenAddons: () -> Unit = {}) {
        composeRule.setContent {
            DynamicTheme {
                WatchScreen(
                    state = state,
                    mediaType = MediaType.MOVIE,
                    season = 0, episode = 0,
                    onBack = onBack,
                    onRetry = onRetry,
                    onOpenAddons = onOpenAddons,
                    onSelectServer = {},
                    onSavePosition = { _, _ -> })
            }
        }
    }

    @Test fun loadingStateShowsWatchChrome() {
        watch(DetailUiState(resolving = true))
        composeRule.onNodeWithText("Watch").assertIsDisplayed()
    }

    @Test fun noAddonsPanelLinksToManager() {
        var addons = 0
        var backs = 0
        watch(
            DetailUiState(providerMissing = true),
            onBack = { backs++ }, onOpenAddons = { addons++ })
        composeRule.onNodeWithText(
            "No stream addons installed. Add one to watch this title.").assertIsDisplayed()
        composeRule.onNodeWithText("Open addon manager").performClick()
        assertEquals(1, addons)
        composeRule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun loadFailedPanelOffersRetry() {
        var retries = 0
        watch(
            DetailUiState(
                detail = MediaDetail(42, MediaType.MOVIE, "The Film"),
                resolveError = "Addon exploded"),
            onRetry = { retries++ })
        composeRule.onNodeWithText("Addon exploded").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun sourceStateBuildsPlayerChrome() {
        watch(
            DetailUiState(
                detail = MediaDetail(42, MediaType.MOVIE, "The Film"),
                source = com.vayunmathur.uniflix.domain.VideoSource(
                    streamUrl = "https://addons.invalid/v.m3u8",
                    title = "Addon Stream",
                    subtitles = listOf(
                        com.vayunmathur.uniflix.domain.SubtitleTrack(
                            "en", "English", "https://addons.invalid/en.vtt")))))
        // Player is built (media3 under Robolectric) and the top chrome shows
        // the detail title; playback itself fails offline into playerError.
        composeRule.onNodeWithText("The Film").assertIsDisplayed()
    }

    @Test fun loadFailedWithoutErrorUsesFallbackMessage() {
        watch(DetailUiState(detail = MediaDetail(42, MediaType.MOVIE, "The Film")))
        composeRule.onNodeWithText("No stream available for this title.").assertIsDisplayed()
    }
}
