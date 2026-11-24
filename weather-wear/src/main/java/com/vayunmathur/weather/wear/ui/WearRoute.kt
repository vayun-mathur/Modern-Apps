package com.vayunmathur.weather.wear.ui
sealed interface WearRoute { data object Locations:WearRoute; data class Location(val locationId:Long):WearRoute }
