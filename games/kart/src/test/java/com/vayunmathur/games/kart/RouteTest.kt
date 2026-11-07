package com.vayunmathur.games.kart

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RouteTest {

    @Test fun gameRouteIsSingletonAndSerializes() {
        assertSame(Route.Game, Route.Game)
        val json = Json.encodeToString(Route.serializer(), Route.Game)
        assertEquals(Route.Game, Json.decodeFromString(Route.serializer(), json))
    }
}
