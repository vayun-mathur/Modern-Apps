package com.vayunmathur.auto

import com.vayunmathur.library.util.NavBackStack
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The car-side stack: [Route.LauncherHome] under at most one [Route.App],
 * projected from the selected app id by [syncCarRoute]. Hosted apps keep
 * their own template stacks; this stack only decides home versus hosted.
 */
@RunWith(RobolectricTestRunner::class)
class CarRouteTest {

    private fun stackOf(vararg routes: Route) = NavBackStack(routes)

    @Test
    fun `null selection is the home grid`() {
        assertEquals(Route.LauncherHome, carRouteFor(null))
        assertEquals(Route.App("maps"), carRouteFor("maps"))
    }

    @Test
    fun `selecting an app pushes it over home`() {
        val stack = stackOf(Route.LauncherHome)
        syncCarRoute(stack, "maps")
        assertEquals(listOf(Route.LauncherHome, Route.App("maps")), stack.backStack)
    }

    @Test
    fun `switching apps replaces instead of stacking`() {
        val stack = stackOf(Route.LauncherHome)
        syncCarRoute(stack, "maps")
        syncCarRoute(stack, "music")
        assertEquals(listOf(Route.LauncherHome, Route.App("music")), stack.backStack)
    }

    @Test
    fun `clearing the selection pops back home`() {
        val stack = stackOf(Route.LauncherHome)
        syncCarRoute(stack, "maps")
        syncCarRoute(stack, null)
        assertEquals(listOf(Route.LauncherHome), stack.backStack)
        assertNull((stack.backStack.lastOrNull() as? Route.App)?.appId)
    }

    @Test
    fun `sync is idempotent while the selection is unchanged`() {
        val stack = stackOf(Route.LauncherHome)
        syncCarRoute(stack, "maps")
        syncCarRoute(stack, "maps")
        syncCarRoute(stack, null)
        syncCarRoute(stack, null)
        assertEquals(listOf(Route.LauncherHome), stack.backStack)
    }

    @Test
    fun `car routes round trip through serialization`() {
        val json = kotlinx.serialization.json.Json
        for (route in listOf<Route>(Route.LauncherHome, Route.App("com.example.maps/.Main"))) {
            val text = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), text))
        }
    }
}
