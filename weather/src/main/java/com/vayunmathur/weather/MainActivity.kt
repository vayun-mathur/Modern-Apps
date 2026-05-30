package com.vayunmathur.weather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.weather.data.WeatherDatabase
import com.vayunmathur.weather.ui.HomePage
import com.vayunmathur.weather.ui.SearchLocationPage
import com.vayunmathur.weather.util.WeatherViewModel
import com.vayunmathur.weather.util.WeatherViewModelFactory
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val db by lazy { buildDatabase<WeatherDatabase>(dbName = "weather-db") }
    private val viewModel: WeatherViewModel by viewModels {
        WeatherViewModelFactory(application, db.weatherDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Home : Route
    @Serializable data object SearchLocation : Route
}

@Composable
fun Navigation(viewModel: WeatherViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home>(metadata = ListPage()) { HomePage(backStack, viewModel) }
        entry<Route.SearchLocation>(metadata = DialogPage()) { SearchLocationPage(backStack, viewModel) }
    }
}
