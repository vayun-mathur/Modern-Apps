package com.vayunmathur.launcher.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.launcher.platform.TvHomeViewModel
import com.vayunmathur.launcher.ui.TvHomePage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack

/**
 * TV navigation: a single Home destination. Phone `Navigation` is untouched — the two
 * graphs share only the activity, never a back stack.
 */
@Composable
fun TvNavigation(
    viewModel: TvHomeViewModel,
    backStack: NavBackStack<TvRoute>,
) {
    // Opaque: a TV home screen paints its own warm gradient, never the wallpaper behind it.
    MainNavigation(backStack) {
        entry<TvRoute.Home> { TvHomePage(viewModel) }
    }
}
