package com.vayunmathur.weather

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.ui.HomePage
import com.vayunmathur.weather.ui.SearchLocationPage
import com.vayunmathur.weather.ui.WeatherMapPage

@Composable
fun Navigation(viewModel: WeatherViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    // No list-detail metadata: Home is not a list and the map is not its
    // detail, so every destination is a full-screen push. Marking both as
    // list panes squeezed the forecast into a narrow pane beside an empty
    // void on wide windows (same shape as web #864).
    MainNavigation(backStack) {
        entry<Route.Home> { HomePage(backStack, viewModel) }
        entry<Route.SearchLocation>(metadata = DialogPage()) { SearchLocationPage(backStack, viewModel) }
        entry<Route.WeatherMap> {
            WeatherMapPage(backStack, it.latitude, it.longitude, it.isoTime, it.metric)
        }
    }
}
