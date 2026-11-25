package com.vayunmathur.news

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.news.platform.NewsViewModel
import com.vayunmathur.news.ui.FeedScreen
import com.vayunmathur.news.ui.LocalLocationScreen
import com.vayunmathur.news.ui.ReaderScreen
import com.vayunmathur.news.ui.SavedScreen
import com.vayunmathur.news.ui.SettingsScreen
import com.vayunmathur.news.ui.StoryScreen

@Composable
fun Navigation(viewModel: NewsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Feed)
    // List/detail panes: Feed, Saved and Settings are lists; Story, Reader
    // and LocalLocation are their details. On medium+ widths MainNavigation
    // shows list + detail side by side (the article URL/story id stay in
    // the Route — selection is navigation, never ViewModel state); phones
    // keep the single-pane push. Opening a story from the still-visible
    // feed replaces an open Story/Reader detail instead of piling stale
    // details onto the back stack.
    MainNavigation(backStack) {
        entry<Route.Feed>(metadata = ListPage()) {
            FeedScreen(
                viewModel = viewModel,
                onOpenStory = { storyId ->
                    backStack.pushOrReplace(Route.Story(storyId)) { it is Route.Story || it is Route.Reader }
                },
                onOpenSaved = { backStack.add(Route.Saved) },
                onOpenSettings = { backStack.add(Route.Settings) },
            )
        }
        entry<Route.Story>(metadata = ListDetailPage()) {
            StoryScreen(
                viewModel = viewModel,
                storyId = it.storyId,
                onOpenArticle = { url -> backStack.add(Route.Reader(url)) },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Reader>(metadata = ListDetailPage()) {
            ReaderScreen(
                viewModel = viewModel,
                url = it.url,
                onBack = { backStack.pop() },
            )
        }
        entry<Route.LocalLocation>(metadata = ListDetailPage()) {
            LocalLocationScreen(viewModel = viewModel, onBack = { backStack.pop() })
        }
        entry<Route.Settings>(metadata = ListPage()) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onOpenLocalLocation = { backStack.add(Route.LocalLocation) },
            )
        }
        entry<Route.Saved>(metadata = ListPage()) {
            SavedScreen(
                viewModel = viewModel,
                onOpenArticle = { url -> backStack.add(Route.Reader(url)) },
                onBack = { backStack.pop() },
            )
        }
    }
}
