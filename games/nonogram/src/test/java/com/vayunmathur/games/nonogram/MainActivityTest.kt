package com.vayunmathur.games.nonogram

import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test fun routesSerializeRoundTrip() {
        val json = kotlinx.serialization.json.Json
        for (route in listOf(Route.Game, Route.GameCenter, Route.Settings)) {
            val text = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), text))
        }
    }

    @Test fun routesAreDistinctObjects() {
        assertEquals(Route.Game, Route.Game)
        assertTrue(Route.Game != Route.Settings)
        assertTrue(Route.GameCenter != Route.Settings)
    }

    @Test @Config(sdk = [34]) fun activityLaunchesAndShowsContent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
    }
}
