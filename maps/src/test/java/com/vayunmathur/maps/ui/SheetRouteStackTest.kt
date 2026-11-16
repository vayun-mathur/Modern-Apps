package com.vayunmathur.maps.ui

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.SheetRoute
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.directionsTo
import com.vayunmathur.maps.effectiveFeature
import com.vayunmathur.maps.parkedRoutePlan
import com.vayunmathur.maps.popSheet
import com.vayunmathur.maps.selectFeature
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sheet-stack transitions: the navigation the sheet routes replaced. */
class SheetRouteStackTest {

    private fun place(name: String) = SpecificFeature.GenericPlace(
        name, null, null, null, GeoPoint(-122.4, 37.8),
    )

    private fun stack() = NavBackStack<SheetRoute>(arrayOf(SheetRoute.Browse))

    @Test
    fun `directions pushes a plan over the place and back returns to it`() {
        val stack = stack()
        stack.selectFeature(place("Cafe"))
        stack.directionsTo(place("Home"))
        assertTrue(stack.last() is SheetRoute.RoutePlan)
        assertEquals(2, (stack.last() as SheetRoute.RoutePlan).waypoints.size)
        stack.popSheet()
        assertEquals(place("Cafe"), (stack.last() as SheetRoute.Place).feature)
    }

    @Test
    fun `a place tapped over a route parks it and directions appends to it`() {
        val stack = stack()
        stack.selectFeature(place("Cafe"))
        stack.directionsTo(place("Home"))
        stack.selectFeature(place("Park"))
        assertEquals(
            SheetRoute.RoutePlan(listOf(null, place("Home"))),
            stack.backStack.parkedRoutePlan(),
        )
        stack.directionsTo(place("Park"))
        assertEquals(
            listOf(null, place("Home"), place("Park")),
            (stack.last() as SheetRoute.RoutePlan).waypoints,
        )
    }

    @Test
    fun `search pops back to what it covered and a pick replaces the place`() {
        val stack = stack()
        stack.selectFeature(place("Cafe"))
        stack.add(SheetRoute.Search(waypointIndex = null, nearLat = 37.8, nearLon = -122.4))
        stack.popSheet()
        assertEquals(place("Cafe"), (stack.last() as SheetRoute.Place).feature)
        stack.add(SheetRoute.Search())
        stack.selectFeature(place("Bakery"))
        assertEquals(place("Bakery"), (stack.last() as SheetRoute.Place).feature)
        assertEquals(2, stack.backStack.size)
    }

    @Test
    fun `repeated place taps replace in place and the root never pops`() {
        val stack = stack()
        stack.selectFeature(place("A"))
        stack.selectFeature(place("B"))
        assertEquals(2, stack.backStack.size)
        stack.popSheet()
        assertEquals(SheetRoute.Browse, stack.last())
        stack.popSheet()
        assertEquals(SheetRoute.Browse, stack.last())
        assertNull(stack.backStack.effectiveFeature())
    }

    @Test
    fun `route mode and poi section live on their routes`() {
        val stack = stack()
        stack.selectFeature(place("Cafe"))
        stack.setLast((stack.last() as SheetRoute.Place).copy(section = PoiSection.PHOTOS))
        assertEquals(PoiSection.PHOTOS, (stack.last() as SheetRoute.Place).section)
        stack.directionsTo(place("Home"))
        stack.setLast((stack.last() as SheetRoute.RoutePlan).copy(mode = RouteService.TravelMode.WALK))
        assertEquals(RouteService.TravelMode.WALK, (stack.last() as SheetRoute.RoutePlan).mode)
    }

    @Test
    fun `a trip pops back to its departures board`() {
        val stack = stack()
        stack.add(SheetRoute.Departures("s1", "Central", 37.8, -122.4))
        stack.add(SheetRoute.Trip(42L, 37.8, -122.4))
        stack.popSheet()
        assertTrue(stack.last() is SheetRoute.Departures)
    }
}
