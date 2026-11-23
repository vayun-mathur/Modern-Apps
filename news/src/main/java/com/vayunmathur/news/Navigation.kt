package com.vayunmathur.news

import androidx.compose.runtime.Composable
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
    MainNavigation(backStack) {
        entry<Route.Feed> {
            FeedScreen(
                viewModel = viewModel,
                onOpenStory = { storyId -> backStack.add(Route.Story(storyId)) },
                onOpenSaved = { backStack.add(Route.Saved) },
                onOpenSettings = { backStack.add(Route.Settings) },
            )
        }
        entry<Route.Story> {
            StoryScreen(
                viewModel = viewModel,
                storyId = it.storyId,
                onOpenArticle = { url -> backStack.add(Route.Reader(url)) },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Reader> {
            ReaderScreen(
                viewModel = viewModel,
                url = it.url,
                onBack = { backStack.pop() },
            )
        }
        entry<Route.LocalLocation> {
            LocalLocationScreen(viewModel = viewModel, onBack = { backStack.pop() })
        }
        entry<Route.Settings> {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onOpenLocalLocation = { backStack.add(Route.LocalLocation) },
            )
        }
        entry<Route.Saved> {
            SavedScreen(
                viewModel = viewModel,
                onOpenArticle = { url -> backStack.add(Route.Reader(url)) },
                onBack = { backStack.pop() },
            )
        }
    }
}
