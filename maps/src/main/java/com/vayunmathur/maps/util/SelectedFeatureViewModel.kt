package com.vayunmathur.maps.util
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.TransitRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position

class SelectedFeatureViewModel(application: Application): AndroidViewModel(application) {
    private val _selectedFeature = MutableStateFlow<SpecificFeature?>(null)
    val selectedFeature = _selectedFeature.asStateFlow()

    private val _inactiveNavigation = MutableStateFlow<SpecificFeature.Route?>(null)
    val inactiveNavigation = _inactiveNavigation.asStateFlow()

    private val _userPosition = MutableStateFlow(Position(0.0, 0.0))
    val userPosition = _userPosition.asStateFlow()

    private val _userBearing = MutableStateFlow(0f)
    val userBearing = _userBearing.asStateFlow()

    init {
        val locationManager = FrameworkLocationManager(application)
        locationManager.startUpdates { position, bearing ->
            _userPosition.value = position
            _userBearing.value = bearing
        }
    }

    fun set(feature: SpecificFeature?) {
        _selectedFeature.value = feature
    }

    fun setInactiveNavigation(route: SpecificFeature.Route?) {
        _inactiveNavigation.value = route
    }

    // Move heavy computation to a background StateFlow
    @OptIn(ExperimentalCoroutinesApi::class)
    val routes = selectedFeature
        .flatMapLatest { feature ->
            val pos = userPosition.value
            val routeFeature = feature as? SpecificFeature.Route ?: return@flatMapLatest flowOf(null)

            // Create a flow that emits results one by one
            flow {
                // Start with an empty map
                emit(emptyMap())

                // Run calculations for each mode
                RouteService.TravelMode.entries.forEach { mode ->
                    val result = try {
                        when (mode) {
                            RouteService.TravelMode.TRANSIT -> TransitRoute.computeRoute(routeFeature, pos)
                            RouteService.TravelMode.BICYCLE, RouteService.TravelMode.WALK -> {
                                OfflineRouter.getRoute(application, routeFeature, pos, mode)
                            }
                            else -> RouteService.computeRoute(routeFeature, pos, mode)
                        }
                    } catch (e: Exception) {
                        RouteService.EmptyRoute()
                    }
                    // Emit the new pair
                    emit(mapOf(mode to result))
                }
            }
                .scan(RouteService.TravelMode.entries.associateWith { null as RouteService.RouteType? }) { accumulator, newEntry ->
                    accumulator + newEntry // Combine the old map with the new calculation
                }
                .flowOn(Dispatchers.Default)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}