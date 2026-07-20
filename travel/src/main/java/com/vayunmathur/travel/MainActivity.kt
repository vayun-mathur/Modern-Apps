package com.vayunmathur.travel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.travel.data.DB_NAME
import com.vayunmathur.travel.data.FavoriteDao
import com.vayunmathur.travel.data.RecentSearchDao
import com.vayunmathur.travel.data.TravelDatabase
import com.vayunmathur.travel.ui.CarResultsPage
import com.vayunmathur.travel.ui.FlightResultsPage
import com.vayunmathur.travel.ui.HomePage
import com.vayunmathur.travel.ui.HotelResultsPage
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.TravelViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var recentSearchDao: RecentSearchDao
    private lateinit var favoriteDao: FavoriteDao

    private val viewModel: TravelViewModel by viewModels {
        TravelViewModelFactory(application, recentSearchDao, favoriteDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val db = buildDatabase<TravelDatabase>(dbName = DB_NAME)
            recentSearchDao = db.recentSearchDao()
            favoriteDao = db.favoriteDao()
            withContext(Dispatchers.Main) { ready.value = true }
        }

        setContent {
            DynamicTheme {
                if (ready.value) MainGraph(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data class FlightResults(
        val origin: String,
        val destination: String,
        val depart: String,
        val returnDate: String?,
        val adults: Int,
    ) : Route

    @Serializable
    data class HotelResults(
        val location: String,
        val checkin: String,
        val checkout: String,
        val adults: Int,
    ) : Route

    @Serializable
    data class CarResults(
        val location: String,
        val pickup: String,
        val dropoff: String,
    ) : Route
}

@Composable
fun MainGraph(viewModel: TravelViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> { HomePage(backStack, viewModel) }
            entry<Route.FlightResults> { FlightResultsPage(backStack, viewModel, it) }
            entry<Route.HotelResults> { HotelResultsPage(backStack, viewModel, it) }
            entry<Route.CarResults> { CarResultsPage(backStack, viewModel, it) }
        }
    }
}
