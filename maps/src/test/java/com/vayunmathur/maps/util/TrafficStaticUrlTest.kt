package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The R2 key the app reads must be the one the server writes (traffic.rs `r2_square_key`). */
class TrafficStaticUrlTest {

    @Test
    fun whole_degree_squares_map_to_their_south_west_corner() {
        assertEquals(
            "https://static.maproj.org/traffic/v1/37_-123.bin",
            OfflineRouterTrafficFetch.staticSquareUrl(37.0, -123.0, 38.0, -122.0),
        )
        assertEquals(
            "https://static.maproj.org/traffic/v1/-34_151.bin",
            OfflineRouterTrafficFetch.staticSquareUrl(-34.0, 151.0, -33.0, 152.0),
        )
        assertEquals(
            "https://static.maproj.org/traffic/v1/0_-1.bin",
            OfflineRouterTrafficFetch.staticSquareUrl(0.0, -1.0, 1.0, 0.0),
        )
    }

    @Test
    fun other_bboxes_have_no_static_copy() {
        assertNull(OfflineRouterTrafficFetch.staticSquareUrl(37.5, -123.0, 38.5, -122.0))
        assertNull(OfflineRouterTrafficFetch.staticSquareUrl(37.0, -123.0, 39.0, -122.0))
    }
}
