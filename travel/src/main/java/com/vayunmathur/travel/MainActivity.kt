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
import com.vayunmathur.travel.data.BookedTripDao
import com.vayunmathur.travel.data.DB_NAME
import com.vayunmathur.travel.data.RecentSearchDao
import com.vayunmathur.travel.data.TravelDatabase
import com.vayunmathur.travel.ui.ConfirmationPage
import com.vayunmathur.travel.ui.FlightResultsPage
import com.vayunmathur.travel.ui.HomePage
import com.vayunmathur.travel.ui.OfferReviewPage
import com.vayunmathur.travel.ui.PassengersPage
import com.vayunmathur.travel.ui.PaymentPage
import com.vayunmathur.travel.ui.TripsPage
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.TravelViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var recentSearchDao: RecentSearchDao
    private lateinit var bookedTripDao: BookedTripDao

    private val viewModel: TravelViewModel by viewModels {
        TravelViewModelFactory(application, recentSearchDao, bookedTripDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val db = buildDatabase<TravelDatabase>(dbName = DB_NAME)
            recentSearchDao = db.recentSearchDao()
            bookedTripDao = db.bookedTripDao()
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
        val cabin: String,
    ) : Route

    @Serializable
    data class OfferReview(val offerId: String) : Route

    @Serializable
    data class Passengers(val offerId: String) : Route

    @Serializable
    data class Payment(val offerId: String) : Route

    @Serializable
    data class Confirmation(val orderId: String) : Route

    @Serializable
    data object Trips : Route
}

@Composable
fun MainGraph(viewModel: TravelViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> { HomePage(backStack, viewModel) }
            entry<Route.FlightResults> { FlightResultsPage(backStack, viewModel, it) }
            entry<Route.OfferReview> { OfferReviewPage(backStack, viewModel, it) }
            entry<Route.Passengers> { PassengersPage(backStack, viewModel, it) }
            entry<Route.Payment> { PaymentPage(backStack, viewModel, it) }
            entry<Route.Confirmation> { ConfirmationPage(backStack, viewModel, it) }
            entry<Route.Trips> { TripsPage(backStack, viewModel) }
        }
    }
}
