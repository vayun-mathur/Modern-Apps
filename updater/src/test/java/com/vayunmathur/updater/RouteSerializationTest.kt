package com.vayunmathur.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class RouteSerializationTest {

    @Test
    fun `the home route round-trips through its nav-key serializer`() {
        val encoded = Json.encodeToString(Route.serializer(), Route.Home)
        val decoded = Json.decodeFromString(Route.serializer(), encoded)
        assertEquals(Route.Home, decoded)
        assertEquals(Route.Home, Json.decodeFromString(Route.Home.serializer(), Json.encodeToString(Route.Home.serializer(), Route.Home)))
    }
}
