package com.vayunmathur.astronomy

import androidx.compose.runtime.Composable
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.ui.ObjectDetailPage
import com.vayunmathur.astronomy.ui.SearchPage
import com.vayunmathur.astronomy.ui.SettingsPage
import com.vayunmathur.astronomy.ui.SkyMapPage
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: AstronomyViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.SkyMap)
    backStack.OpenSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.SkyMap>(metadata = ListPage()) { SkyMapPage(backStack, viewModel) }
        entry<Route.ObjectDetail>(metadata = DialogPage()) { route -> ObjectDetailPage(backStack, viewModel, route.id) }
        entry<Route.Search>(metadata = ListPage()) { SearchPage(backStack, viewModel) }
        entry<Route.Settings>(metadata = ListPage()) { SettingsPage(backStack, viewModel) }
        entry<Route.HistoryDatePicker>(metadata = DialogPage()) { route ->
            DatePickerDialog(backStack, "AstroHistoryDatePicker", route.initialDate)
        }
    }
}
