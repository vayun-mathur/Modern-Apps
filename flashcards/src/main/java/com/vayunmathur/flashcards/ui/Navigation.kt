package com.vayunmathur.flashcards.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

/** Root navigation: tabbed decks/stats/settings plus pushed note and review destinations. */
@Composable
fun Navigation(viewModel: FlashcardsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Decks)
    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf<RouteTab<Route>>(
            RouteTab(Route.Decks, stringResource(R.string.nav_decks)) { IconStyle() },
            RouteTab(Route.Stats, stringResource(R.string.nav_stats)) { IconDashboard() },
            RouteTab(Route.Settings, stringResource(R.string.nav_settings)) { IconSettings() },
        ),
    ) {
    MainNavigation(backStack) {
        entry<Route.Decks>(metadata = ListPage() + SiblingPage()) { DeckListPage(backStack, viewModel) }
        entry<Route.Settings>(metadata = SiblingPage()) { SettingsPage(backStack, viewModel) }
        entry<Route.Stats>(metadata = ListDetailPage() + SiblingPage()) { StatsPage(backStack, viewModel) }
        entry<Route.NoteTypeList>(metadata = ListDetailPage()) { NoteTypeListPage(backStack, viewModel) }
        entry<Route.NoteTypeEdit>(metadata = ListDetailPage()) { NoteTypeEditPage(backStack, viewModel, it.noteTypeId) }
        entry<Route.CardList>(metadata = ListDetailPage() + MorphPage()) {
            NoteListPage(backStack, viewModel, it.deckId)
        }
        entry<Route.NoteEdit>(metadata = ListDetailPage()) { NoteEditPage(backStack, viewModel, it.deckId, it.noteId) }
        entry<Route.Review>(metadata = FullscreenPage()) {
            ReviewPage(backStack, viewModel, it.deckId, it.mode, it.count, it.daysAhead, it.tags)
        }
    }
    }
}
