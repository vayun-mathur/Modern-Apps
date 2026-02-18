package com.vayunmathur.maps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.maplibre.spatialk.geojson.Position

class SelectedFeatureViewModel(application: Application): AndroidViewModel(application) {
    fun set(route: SpecificFeature?) {
        _selectedFeature.value = route
    }

    init {
        val locationManager = FrameworkLocationManager(application)
        locationManager.startUpdates { position, bearing ->
            _userPosition.value = position
            _userBearing.value = bearing
        }
    }

    fun setInactiveNavigation(route: SpecificFeature.Route?) {
        _inactiveNavigation.value = route
    }

    private val _selectedFeature = MutableStateFlow<SpecificFeature?>(null)
    val selectedFeature = _selectedFeature.asStateFlow()

    private val _inactiveNavigation = MutableStateFlow<SpecificFeature.Route?>(null)
    val inactiveNavigation = _inactiveNavigation.asStateFlow()

    private val _userPosition = MutableStateFlow(Position(0.0, 0.0))
    val userPosition = _userPosition.asStateFlow()

    private val _userBearing = MutableStateFlow(0f)
    val userBearing = _userBearing.asStateFlow()

    val routes = selectedFeature.map {
        val routeFeature = selectedFeature.value as? SpecificFeature.Route ?: return@map null
        RouteService.TravelMode.entries.associateWith {
            if(it == RouteService.TravelMode.TRANSIT) {
                TransitRoute.computeRoute(routeFeature, userPosition.value)
            } else {
                RouteService.computeRoute(routeFeature, userPosition.value, it)
            }
        }
    }
}