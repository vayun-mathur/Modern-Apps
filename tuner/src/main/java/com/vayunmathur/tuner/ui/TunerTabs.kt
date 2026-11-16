package com.vayunmathur.tuner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconMusicNote
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.Route
import com.vayunmathur.tuner.platform.TunerViewModel

/**
 * The app's two tabs as sibling [Route] destinations.
 *
 * Capture follows the tabs being on screen: there is no control for it, because a tuner that is
 * open and not listening has nothing to offer. Starting from here rather than from the activity
 * is what orders it after the permission gate, which the activity's start hook sits in front of.
 * After that the activity's start/stop hooks release and restore the microphone around the
 * foreground. Disposal is a full stop, not a pause. The effect lives at this host level, so
 * switching between the Note and Chord tabs does not restart capture.
 */
@Composable
fun TunerTabs(backStack: NavBackStack<Route>, viewModel: TunerViewModel) {
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf<RouteTab<Route>>(
            RouteTab(Route.Note, stringResource(R.string.tab_note)) { IconMusicNote() },
            RouteTab(Route.Chord, stringResource(R.string.tab_chord)) { IconGrid() },
        ),
    ) {
        MainNavigation(backStack) {
            entry<Route.Note>(metadata = SiblingPage()) { NotePage(viewModel) }
            entry<Route.Chord>(metadata = SiblingPage()) { ChordPage(viewModel) }
        }
    }
}
