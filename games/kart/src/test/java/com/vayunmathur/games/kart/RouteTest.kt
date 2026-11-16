package com.vayunmathur.games.kart

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RouteTest {

    @Test fun raceRoutesAreSingletonsAndSerialize() {
        assertSame(Route.Lobby, Route.Lobby)
        assertSame(Route.Race, Route.Race)
        assertSame(Route.Results, Route.Results)
        for (route in listOf(Route.Lobby, Route.Race, Route.Results)) {
            val json = Json.encodeToString(Route.serializer(), route)
            assertEquals(route, Json.decodeFromString(Route.serializer(), json))
        }
    }
}
