package com.vayunmathur.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.Dispatchers
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
    val routes = selectedFeature
        .map { feature ->
            val pos = userPosition.value
            val routeFeature = feature as? SpecificFeature.Route ?: return@map null

            // Perform heavy routing on Dispatchers.Default
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                RouteService.TravelMode.entries.associateWith { mode ->
                    try {
                        when (mode) {
                            RouteService.TravelMode.TRANSIT -> {
                                TransitRoute.computeRoute(routeFeature, pos)
                            }
                            RouteService.TravelMode.BICYCLE, RouteService.TravelMode.WALK -> {
                                OfflineRouter.getRoute(
                                    application.applicationContext,
                                    routeFeature,
                                    pos,
                                    mode
                                )
                            }
                            else -> {
                                RouteService.computeRoute(routeFeature, pos, mode)
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}