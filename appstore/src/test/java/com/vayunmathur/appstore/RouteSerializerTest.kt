package com.vayunmathur.appstore

import com.vayunmathur.appstore.tv.TvStoreRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json

/**
 * Navigation keys survive serialization: the back stack is saved and
 * restored through these serializers, so every destination — objects
 * and the argument-carrying classes, on both phone and TV — must
 * round-trip to an equal key.
 */
class RouteSerializerTest {

    private val json = Json

    @Test
    fun `phone routes round trip through their serializer`() {
        val browse: Route = Route.Browse("editors-choice")
        val restoredBrowse = json.decodeFromString(
            Route.serializer(),
            json.encodeToString(Route.serializer(), browse),
        )
        assertEquals(browse, restoredBrowse)
        assertEquals("editors-choice", assertIs<Route.Browse>(restoredBrowse).sectionId)

        for (route in listOf<Route>(Route.Main, Route.Trust, Route.Detail)) {
            val restored = json.decodeFromString(
                Route.serializer(),
                json.encodeToString(Route.serializer(), route),
            )
            assertEquals(route, restored)
        }
    }

    @Test
    fun `tv routes round trip through their serializer`() {
        val detail: TvStoreRoute = TvStoreRoute.Detail("com.example.app")
        val restoredDetail = json.decodeFromString(
            TvStoreRoute.serializer(),
            json.encodeToString(TvStoreRoute.serializer(), detail),
        )
        assertEquals(detail, restoredDetail)
        assertEquals("com.example.app", assertIs<TvStoreRoute.Detail>(restoredDetail).packageName)

        val browse: TvStoreRoute = TvStoreRoute.Browse("recent")
        assertEquals(
            browse,
            json.decodeFromString(
                TvStoreRoute.serializer(),
                json.encodeToString(TvStoreRoute.serializer(), browse),
            ),
        )
        assertEquals(
            TvStoreRoute.Main,
            json.decodeFromString(
                TvStoreRoute.serializer(),
                json.encodeToString(TvStoreRoute.serializer(), TvStoreRoute.Main),
            ),
        )
    }
}
