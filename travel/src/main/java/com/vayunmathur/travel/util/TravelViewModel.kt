package com.vayunmathur.travel.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.Favorite
import com.vayunmathur.travel.data.FavoriteDao
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.RecentSearchDao
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.CarDto
import com.vayunmathur.travel.network.FlightDto
import com.vayunmathur.travel.network.HotelDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Generic search state for one vertical's results list. */
data class SearchUiState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<T> = emptyList(),
    val hasSearched: Boolean = false,
)

/**
 * The single hub for the Travel app: owns the per-vertical search state (calling
 * [TravelApi] and exposing loading/error/results as [StateFlow]) and persists
 * recent searches + favorites through the Room [RecentSearchDao] / [FavoriteDao].
 *
 * Modeled after [com.vayunmathur.weather.util.WeatherViewModel] and
 * `EducationViewModel` (manual [ViewModelProvider.Factory]).
 */
class TravelViewModel(
    application: Application,
    private val recentSearchDao: RecentSearchDao,
    private val favoriteDao: FavoriteDao,
) : AndroidViewModel(application) {

    private val _flights = MutableStateFlow(SearchUiState<FlightDto>())
    val flights: StateFlow<SearchUiState<FlightDto>> = _flights.asStateFlow()

    private val _hotels = MutableStateFlow(SearchUiState<HotelDto>())
    val hotels: StateFlow<SearchUiState<HotelDto>> = _hotels.asStateFlow()

    private val _cars = MutableStateFlow(SearchUiState<CarDto>())
    val cars: StateFlow<SearchUiState<CarDto>> = _cars.asStateFlow()

    val recentSearches: StateFlow<List<RecentSearch>> = recentSearchDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<Favorite>> = favoriteDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Autocomplete -----------------------------------------------------

    /** Airport/city suggestions for [query]; never throws (empty on failure). */
    suspend fun autocomplete(query: String): List<PlaceDto> =
        runCatching { TravelApi.places(query) }.getOrDefault(emptyList())

    // --- Searches ---------------------------------------------------------

    fun searchFlights(
        origin: String,
        destination: String,
        depart: String,
        ret: String?,
        adults: Int,
        currency: String = "usd",
    ) {
        recordRecent(
            RecentSearch(
                vertical = Vertical.FLIGHTS.name,
                label = "$origin → $destination · ${prettyDate(depart)}",
                origin = origin,
                destination = destination,
                depart = depart,
                returnDate = ret,
                adults = adults,
            )
        )
        _flights.value = SearchUiState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching { TravelApi.flights(origin, destination, depart, ret, adults, currency) }
                .onSuccess { _flights.value = SearchUiState(results = it, hasSearched = true) }
                .onFailure { _flights.value = SearchUiState(error = errorMessage(it), hasSearched = true) }
        }
    }

    fun searchHotels(
        location: String,
        checkin: String,
        checkout: String,
        adults: Int,
        currency: String = "usd",
    ) {
        recordRecent(
            RecentSearch(
                vertical = Vertical.HOTELS.name,
                label = "$location · ${prettyDate(checkin)}–${prettyDate(checkout)}",
                location = location,
                checkin = checkin,
                checkout = checkout,
                adults = adults,
            )
        )
        _hotels.value = SearchUiState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching { TravelApi.hotels(location, checkin, checkout, adults, currency) }
                .onSuccess { _hotels.value = SearchUiState(results = it, hasSearched = true) }
                .onFailure { _hotels.value = SearchUiState(error = errorMessage(it), hasSearched = true) }
        }
    }

    fun searchCars(
        location: String,
        pickup: String,
        dropoff: String,
        currency: String = "usd",
    ) {
        recordRecent(
            RecentSearch(
                vertical = Vertical.CARS.name,
                label = "$location · ${prettyDate(pickup)}–${prettyDate(dropoff)}",
                location = location,
                pickup = pickup,
                dropoff = dropoff,
            )
        )
        _cars.value = SearchUiState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching { TravelApi.cars(location, pickup, dropoff, currency) }
                .onSuccess { _cars.value = SearchUiState(results = it, hasSearched = true) }
                .onFailure { _cars.value = SearchUiState(error = errorMessage(it), hasSearched = true) }
        }
    }

    // --- Favorites --------------------------------------------------------

    fun isFavorite(bookingUrl: String): Boolean =
        favorites.value.any { it.bookingUrl == bookingUrl }

    /** Add the offer to favorites, or remove it if it's already saved. */
    fun toggleFavorite(favorite: Favorite) {
        viewModelScope.launch {
            if (favorites.value.any { it.bookingUrl == favorite.bookingUrl }) {
                favoriteDao.deleteByUrl(favorite.bookingUrl)
            } else {
                favoriteDao.upsert(favorite)
            }
        }
    }

    fun removeFavorite(favorite: Favorite) {
        viewModelScope.launch { favoriteDao.deleteByUrl(favorite.bookingUrl) }
    }

    // --- Recents ----------------------------------------------------------

    fun clearRecents() {
        viewModelScope.launch { recentSearchDao.clear() }
    }

    private fun recordRecent(search: RecentSearch) {
        viewModelScope.launch {
            recentSearchDao.insert(search)
            recentSearchDao.trim()
        }
    }

    companion object {
        /** "2026-09-01" -> "Sep 1"; falls back to the raw string on any parse error. */
        fun prettyDate(iso: String): String = runCatching {
            val date = java.time.LocalDate.parse(iso.take(10))
            date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        }.getOrDefault(iso)

        private fun errorMessage(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }
}

class TravelViewModelFactory(
    private val application: Application,
    private val recentSearchDao: RecentSearchDao,
    private val favoriteDao: FavoriteDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TravelViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return TravelViewModel(application, recentSearchDao, favoriteDao) as T
    }
}
