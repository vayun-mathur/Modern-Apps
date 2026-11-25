package com.vayunmathur.maps.wear.ui

/** Watch navigation destinations mirroring the phone IA: map home, route detail, saved glance. */
sealed interface WearRoute {
    /** The rendered map (home). */
    data object Map : WearRoute

    /** Maneuver list for the phone's active route. */
    data object RouteDetail : WearRoute

    /** Saved places glance (name + distance; the phone has no Home/Work). */
    data object Saved : WearRoute
}
