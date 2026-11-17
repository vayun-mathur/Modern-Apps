package com.vayunmathur.measure

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.ui.ArMeasurePage
import com.vayunmathur.measure.ui.CompassPage
import com.vayunmathur.measure.ui.DiagnosticsPage
import com.vayunmathur.measure.ui.LevelPage
import com.vayunmathur.measure.ui.RulerPage
import com.vayunmathur.measure.ui.SavedMeasurementsPage
import com.vayunmathur.measure.ui.SettingsPage

@Composable
fun Navigation(viewModel: MeasureViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Compass)
    backStack.OpenSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Compass>(metadata = SiblingPage()) { CompassPage(backStack, viewModel) }
        entry<Route.Level>(metadata = SiblingPage()) { LevelPage(backStack, viewModel) }
        entry<Route.Ruler>(metadata = SiblingPage()) { RulerPage(backStack, viewModel) }
        entry<Route.ArMeasure>(metadata = SiblingPage()) { ArMeasurePage(backStack, viewModel) }
        entry<Route.Saved> { SavedMeasurementsPage(backStack, viewModel) }
        entry<Route.Settings>(metadata = DialogPage()) { SettingsPage(backStack, viewModel) }
        entry<Route.Diagnostics>(metadata = DialogPage()) { DiagnosticsPage(backStack, viewModel) }
    }
}
