package com.vayunmathur.flashcards

import androidx.activity.viewModels
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.ui.DeckListPage
import com.vayunmathur.flashcards.ui.NoteEditPage
import com.vayunmathur.flashcards.ui.NoteListPage
import com.vayunmathur.flashcards.ui.NoteTypeEditPage
import com.vayunmathur.flashcards.ui.NoteTypeListPage
import com.vayunmathur.flashcards.ui.ReviewPage
import com.vayunmathur.flashcards.ui.SettingsPage
import com.vayunmathur.flashcards.ui.StatsPage
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.FlashcardsViewModelFactory
import com.vayunmathur.flashcards.util.ThemeMode
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

/** Entry activity that owns the [FlashcardsViewModel] and applies the theme from its settings. */
class MainActivity : MaActivity() {
    private val viewModel: FlashcardsViewModel by viewModels {
        FlashcardsViewModelFactory(application, FlashcardsRepository.get(application))
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        val darkTheme = when (settings.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> null
        }
        DynamicTheme(darkTheme = darkTheme, content = content)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}

/** Navigation destinations for decks, notes, note types, review, and stats. */
@Serializable
sealed interface Route : NavKey {
    /** The deck-list main screen. */
    @Serializable
    data object Main : Route

    /** The review-statistics screen. */
    @Serializable
    data object Stats : Route

    /** The note-type management list. */
    @Serializable
    data object NoteTypeList : Route

    /** The editor for the note type identified by [noteTypeId]. */
    @Serializable
    data class NoteTypeEdit(val noteTypeId: Long) : Route

    /** The note list for the deck identified by [deckId]. */
    @Serializable
    data class CardList(val deckId: Long) : Route

    /** The editor for note [noteId] in deck [deckId]. */
    @Serializable
    data class NoteEdit(val deckId: Long, val noteId: Long) : Route

    /** A review session for deck [deckId] with its study mode and limits. */
    @Serializable
    data class Review(
        val deckId: Long,
        val mode: Int = 0,
        val count: Int = 20,
        val daysAhead: Int = 3,
        val tags: List<String> = emptyList(),
    ) : Route
}

@Composable
fun Navigation(viewModel: FlashcardsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    MainNavigation(backStack) {
        entry<Route.Main>(metadata = ListPage()) { FlashcardsTabs(backStack, viewModel) }
        entry<Route.Stats>(metadata = ListDetailPage()) { StatsPage(backStack, viewModel) }
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

/**
 * The three bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * NoteTypeList, NoteTypeEdit, CardList, NoteEdit and Review are pushed on top of this host
 * as ordinary routes. Stats is kept as a standalone pushed route because
 * NoteListPage.openStats() pushes Route.Stats via backStack.add().
 */
@Composable
private fun FlashcardsTabs(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.nav_decks), { IconStyle() }) { DeckListPage(backStack, viewModel) },
        PagerTab(stringResource(R.string.nav_stats), { IconDashboard() }) { StatsPage(backStack, viewModel) },
        PagerTab(stringResource(R.string.nav_settings), { IconSettings() }) { SettingsPage(backStack, viewModel) },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
