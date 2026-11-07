package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.AddonRow
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Compose-under-Robolectric tests for the small catalog/addon cards. */
@RunWith(RobolectricTestRunner::class)
class UniflixCardsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun mediaCardShowsTitleYearAndOpens() {
        var opened = 0
        val item = MediaItem(
            tmdbId = 1, mediaType = MediaType.MOVIE, title = "A Film", year = 2024)
        composeRule.setContent {
            DynamicTheme { MediaCard(item = item, onOpen = { opened++ }) }
        }
        composeRule.onNodeWithText("A Film").assertIsDisplayed()
        composeRule.onNodeWithText("2024").assertIsDisplayed()
        composeRule.onNodeWithText("A Film").performClick()
        assertEquals(1, opened)
    }

    @Test fun mediaRowShowsSectionAndItems() {
        val items = listOf(
            MediaItem(tmdbId = 1, mediaType = MediaType.MOVIE, title = "One"),
            MediaItem(tmdbId = 2, mediaType = MediaType.TV, title = "Two"))
        var opened: MediaItem? = null
        composeRule.setContent {
            DynamicTheme { MediaRow(title = "Trending", items = items, onOpen = { opened = it }) }
        }
        composeRule.onNodeWithText("Trending").assertIsDisplayed()
        composeRule.onNodeWithText("One").assertIsDisplayed()
        composeRule.onNodeWithText("Two").performClick()
        assertEquals(items[1], opened)
    }

    @Test fun episodeCardShowsNumberAndPlays() {
        var plays = 0
        composeRule.setContent {
            DynamicTheme {
                EpisodeCard(
                    episode = Episode(1, 3, "Third", overview = "It happens"),
                    onPlay = { plays++ })
            }
        }
        composeRule.onNodeWithText("E3 · Third").assertIsDisplayed()
        composeRule.onNodeWithText("It happens").assertIsDisplayed()
        composeRule.onNodeWithText("E3 · Third").performClick()
        assertEquals(1, plays)
    }

    @Test fun addonCardShowsHealthAndActions() {
        val entity = AddonEntity(
            dbId = 4, manifestUrl = "https://a.example/manifest.json",
            baseUrl = "https://a.example", addonId = "a", name = "Alpha",
            version = "1.2", enabled = true, lastError = "unreachable")
        var toggles = 0
        var details = 0
        composeRule.setContent {
            DynamicTheme {
                AddonCard(
                    row = AddonRow(entity = entity, isDefault = true),
                    onToggle = { toggles++ },
                    onSetDefault = {}, onClearDefault = {},
                    onMoveUp = {}, onMoveDown = {}, onRemove = {},
                    onRefresh = {}, onOpenDetail = { details++ })
            }
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("v1.2").assertIsDisplayed()
        composeRule.onNodeWithText("unreachable").assertIsDisplayed()
        composeRule.onNodeWithText("Details").performClick()
        assertEquals(1, details)
        assertEquals(0, toggles)
    }
}
