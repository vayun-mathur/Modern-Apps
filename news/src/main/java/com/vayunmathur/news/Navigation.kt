package com.vayunmathur.news

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.news.platform.NewsViewModel
import com.vayunmathur.news.ui.FeedScreen
import com.vayunmathur.news.ui.ReaderScreen
import com.vayunmathur.news.ui.SavedScreen
import com.vayunmathur.news.ui.SourcesScreen

@Composable
fun Navigation(viewModel: NewsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Feed)
    MainNavigation(backStack) {
        entry<Route.Feed> {
            FeedScreen(
                viewModel = viewModel,
                onOpenArticle = { url -> backStack.add(Route.Reader(url)) },
                onOpenSources = { backStack.add(Route.Sources) },
                onOpenSaved = { backStack.add(Route.Saved) },
            )
        }
        entry<Route.Reader> {
            ReaderScreen(
                viewModel = viewModel,
                url = it.url,
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Sources> {
            SourcesScreen(viewModel = viewModel, onBack = { backStack.pop() })
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
