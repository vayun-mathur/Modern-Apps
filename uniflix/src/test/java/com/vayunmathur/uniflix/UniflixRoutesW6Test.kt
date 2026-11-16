package com.vayunmathur.uniflix

import com.vayunmathur.uniflix.ui.tv.TvRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** W6: every phone and TV route survives its generated serializer. */
class UniflixRoutesW6Test {

    @Test
    fun phoneRoutes_roundTrip() {
        val routes: List<Route> = listOf(
            Route.Home, Route.Movies, Route.Series, Route.Search, Route.Addons,
            Route.Detail("movie", 10), Route.Detail("tv", 20),
            Route.Watch("movie", 10, 0, 0), Route.Watch("tv", 20, 1, 2),
            Route.AddonDetail(7L),
        )
        for (route in routes) {
            val text = Json.encodeToString(Route.serializer(), route)
            assertTrue(text.isNotBlank())
            assertEquals(route, Json.decodeFromString(Route.serializer(), text))
        }
    }

    @Test
    fun tvRoutes_roundTrip() {
        val routes: List<TvRoute> = listOf(
            TvRoute.Search, TvRoute.Home, TvRoute.Movies, TvRoute.Shows,
            TvRoute.MyList, TvRoute.Addons,
            TvRoute.Detail("movie", 10), TvRoute.Watch("tv", 20, 1, 1),
            TvRoute.Grid("movies"), TvRoute.AddonDetail(3L),
        )
        for (route in routes) {
            val text = Json.encodeToString(TvRoute.serializer(), route)
            assertEquals(route, Json.decodeFromString(TvRoute.serializer(), text))
        }
    }
}
