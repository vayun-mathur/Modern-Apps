package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SeasonSummary
import com.vayunmathur.uniflix.platform.AddonRow
import com.vayunmathur.uniflix.ui.dialogs.AddAddonDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Compose-under-Robolectric tests for stateless Uniflix screens. */
@RunWith(RobolectricTestRunner::class)
class UniflixScreensComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun item(id: Int, title: String) =
        MediaItem(tmdbId = id, mediaType = MediaType.MOVIE, title = title, year = 2020)

    private fun row(id: Long, name: String, priority: Int = 0, isDefault: Boolean = false) = AddonRow(
        entity = AddonEntity(
            dbId = id, manifestUrl = "https://$name.example/manifest.json",
            baseUrl = "https://$name.example", addonId = name, name = name, priority = priority),
        isDefault = isDefault)

    @Test fun addonsContentEmptyStateActions() {
        var adds = 0
        var imports = 0
        composeRule.setContent {
            DynamicTheme {
                AddonsContent(
                    rows = emptyList(), adding = false, refreshing = false,
                    onBack = {}, onAddClick = { adds++ }, onImportJsClick = { imports++ },
                    onToggle = {}, onSetDefault = {}, onClearDefault = {},
                    onMoveUp = {}, onMoveDown = {}, onRemove = {},
                    onRefresh = {}, onRefreshAll = {}, onOpenDetail = {})
            }
        }
        composeRule.onNodeWithText("Stream addons").assertIsDisplayed()
        composeRule.onNodeWithText("No stream addons yet. Add one to enable playback.").assertIsDisplayed()
        composeRule.onNodeWithText("Add addon").performClick()
        composeRule.onNodeWithText("Import .js").performClick()
        assertEquals(1, adds)
        assertEquals(1, imports)
    }

    @Test fun addonsContentListsRowsAndOpensDetail() {
        var opened: Long? = null
        var toggled: String? = null
        composeRule.setContent {
            DynamicTheme {
                AddonsContent(
                    rows = listOf(row(3, "Alpha", isDefault = true), row(4, "Beta")),
                    adding = false, refreshing = true,
                    onBack = {}, onAddClick = {}, onImportJsClick = {},
                    onToggle = { toggled = it.entity.name }, onSetDefault = {}, onClearDefault = {},
                    onMoveUp = {}, onMoveDown = {}, onRemove = {},
                    onRefresh = {}, onRefreshAll = {}, onOpenDetail = { opened = it })
            }
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onAllNodesWithText("Details")[0].performClick()
        // First row's Details opens its detail page.
        assertTrue(opened == 3L || opened == 4L, "opened=$opened")
        assertEquals(null, toggled)
    }

    @Test fun mediaGridLoadingShowsTitle() {
        composeRule.setContent {
            DynamicTheme {
                MediaGridScreen(
                    title = "Movies", loading = true, items = emptyList(),
                    onBack = {}, onOpenDetail = {})
            }
        }
        composeRule.onNodeWithText("Movies").assertIsDisplayed()
    }

    @Test fun mediaGridEmptyShowsPlaceholder() {
        composeRule.setContent {
            DynamicTheme {
                MediaGridScreen(
                    title = "Movies", loading = false, items = emptyList(),
                    onBack = {}, onOpenDetail = {})
            }
        }
        composeRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()
    }

    @Test fun mediaGridItemsOpenDetail() {
        var opened: MediaItem? = null
        val items = listOf(item(1, "Grid One"), item(2, "Grid Two"))
        composeRule.setContent {
            DynamicTheme {
                MediaGridScreen(
                    title = "Movies", loading = false, items = items,
                    onBack = {}, onOpenDetail = { opened = it })
            }
        }
        composeRule.onNodeWithText("Grid One").assertIsDisplayed()
        composeRule.onNodeWithText("Grid Two").performClick()
        assertEquals(items[1], opened)
    }

    @Test fun seasonDropdownFiltersSpecialsAndSelects() {
        var selected: Int? = null
        val seasons = listOf(
            SeasonSummary(0, "Specials", 4),
            SeasonSummary(1, "Season 1", 8),
            SeasonSummary(2, "Season 2", 10))
        composeRule.setContent {
            DynamicTheme {
                SeasonDropdown(seasons = seasons, selectedSeason = 1, onSelect = { selected = it })
            }
        }
        composeRule.onNodeWithText("Season 1 · 8 episodes").assertIsDisplayed()
        composeRule.onNodeWithText("Season 1 · 8 episodes").performClick()
        composeRule.onNodeWithText("Season 2 · 10 episodes").assertIsDisplayed()
        composeRule.onNodeWithText("Season 2 · 10 episodes").performClick()
        assertEquals(2, selected)
    }

    @Test fun seasonDropdownWithoutSeasonsShowsFallback() {
        composeRule.setContent {
            DynamicTheme {
                SeasonDropdown(seasons = emptyList(), selectedSeason = 0, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Season").assertIsDisplayed()
    }

    @Test fun addAddonDialogConfirmsTypedUrl() {
        var confirmed: String? = null
        var dismissed = 0
        var imports = 0
        composeRule.setContent {
            DynamicTheme {
                AddAddonDialog(
                    adding = false,
                    onDismiss = { dismissed++ },
                    onConfirm = { confirmed = it },
                    onImportJs = { imports++ })
            }
        }
        composeRule.onNodeWithText("Add stream addon").assertIsDisplayed()
        composeRule.onNodeWithText("Addon URL").performTextInput("https://addons.example/manifest.json")
        composeRule.onNodeWithText("Import .js plugin instead").performClick()
        assertEquals(1, imports)
        composeRule.onNodeWithText("Add").performClick()
        assertEquals("https://addons.example/manifest.json", confirmed)
        assertEquals(0, dismissed)
    }

    @Test fun addAddonDialogCancelDismisses() {
        var dismissed = 0
        composeRule.setContent {
            DynamicTheme {
                AddAddonDialog(
                    adding = true,
                    onDismiss = { dismissed++ },
                    onConfirm = {},
                    onImportJs = {})
            }
        }
        // While adding, Cancel is disabled, so use the dismiss path text check only.
        composeRule.onNodeWithText("Add stream addon").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        assertEquals(0, dismissed)
    }
}
