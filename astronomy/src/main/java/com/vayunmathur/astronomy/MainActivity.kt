package com.vayunmathur.astronomy

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.astronomy.ui.pages.ArSkyPage
import com.vayunmathur.astronomy.ui.pages.ObjectDetailPage
import com.vayunmathur.astronomy.ui.pages.SearchPage
import com.vayunmathur.astronomy.ui.pages.SettingsPage
import com.vayunmathur.astronomy.ui.pages.SkyMapPage
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: AstronomyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                // Mandatory location gate like Maps app — astronomy needs lat/lon for LST + horizon
                PermissionsChecker(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    "Grant location permission — astronomy needs your position for horizon"
                ) {
                    Navigation(viewModel)
                }
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object SkyMap : Route
    @Serializable data object ArSky : Route
    @Serializable data class ObjectDetail(val id: String) : Route
    @Serializable data object Search : Route
    @Serializable data object Settings : Route
}

@Composable
fun Navigation(viewModel: AstronomyViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.SkyMap)
    MainNavigation(backStack) {
        entry<Route.SkyMap>(metadata = ListPage()) { SkyMapPage(backStack, viewModel) }
        entry<Route.ArSky>(metadata = ListPage()) { ArSkyPage(backStack, viewModel) }
        entry<Route.ObjectDetail>(metadata = DialogPage()) { route -> ObjectDetailPage(backStack, viewModel, route.id) }
        entry<Route.Search>(metadata = DialogPage()) { SearchPage(backStack, viewModel) }
        entry<Route.Settings>(metadata = DialogPage()) { SettingsPage(backStack, viewModel) }
    }
}
