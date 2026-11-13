package com.vayunmathur.maps.util

import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint

/**
 * The world, used when the camera cannot say what is on screen.
 *
 * Latitude stops at ±85° rather than ±90° because that is where the web-mercator projection
 * the basemap uses runs out; the poles are not addressable in it.
 */
private val World = GeoBounds(west = -180.0, south = -85.0, east = 180.0, north = 85.0)

/**
 * The visible map bounds, falling back to the whole world.
 *
 * `camera.projection` is null until the first layout measures the viewport, which is a state
 * a user can reach — tapping search during the first frames after launch. Every caller wants
 * the same fallback for the same reason (a search biased to the world still works; a search
 * biased to nothing does not), and each was spelling out four `?:` defaults, so a typo in any
 * one of them would have silently biased results to the wrong hemisphere.
 */
fun CameraState.visibleBoundsOrWorld(): GeoBounds =
    projection?.queryVisibleBoundingBox() ?: World

/**
 * Where a search should be centred: the user's own position when they are looking at it,
 * otherwise the middle of the screen.
 *
 * Search used to rank from the screen centre only, so with the map nudged a few km away
 * (or zoomed out to the metro) "pizza" was ranked from wherever the middle of the screen
 * happened to be rather than from the person holding the phone. The fix only wins while it
 * is on screen: a user who pans to another city and searches there means that city. No fix
 * yet is (0, 0) — the same "unset" the rest of the map uses.
 */
fun searchBiasPoint(bounds: GeoBounds, user: GeoPoint): GeoPoint {
    val hasFix = user.latitude != 0.0 || user.longitude != 0.0
    val lonInside = if (bounds.west <= bounds.east) {
        user.longitude in bounds.west..bounds.east
    } else {
        // The view straddles the antimeridian.
        user.longitude >= bounds.west || user.longitude <= bounds.east
    }
    return if (hasFix && lonInside && user.latitude in bounds.south..bounds.north) {
        user
    } else {
        GeoPoint((bounds.east + bounds.west) / 2.0, (bounds.north + bounds.south) / 2.0)
    }
}
