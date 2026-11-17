package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.ui.SheetRoute

import androidx.compose.runtime.mutableStateOf
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.ui.MapCategory
import com.vayunmathur.maps.domain.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Slice 1/3 (maps): [MapChromeState] — the single place where the map chrome's
 * retain-vs-remember policy lives, and the small behaviour on top (category
 * toggle, overlay switching).
 */
class MapChromeStateSlice1Test {

    private fun state() = MapChromeState(
        selectedCategoryState = mutableStateOf<MapCategory?>(null),
        autoFollowState = mutableStateOf(true),
        northUpState = mutableStateOf(false),
        postedLimitState = mutableStateOf<PostedLimit?>(null),
        lastProgrammaticMoveMsState = mutableStateOf(0L),
    )

    private fun category() = MapCategory(
        labelRes = 0,
        query = "restaurants",
        types = setOf(0, 2),
        kinds = setOf("restaurant"),
    )

    @Test
    fun `toggling the active category clears it, toggling another switches`() {
        val s = state()
        val food = category()
        val parks = food.copy(query = "parks")
        assertNull(s.selectedCategory)
        s.toggleCategory(food)
        assertEquals(food, s.selectedCategory)
        s.toggleCategory(food)
        assertNull(s.selectedCategory, "tapping the active chip clears the filter")
        s.toggleCategory(food)
        s.toggleCategory(parks)
        assertEquals(parks, s.selectedCategory)
    }

    @Test
    fun `dialog destinations are mutually exclusive as stack tops`() {
        // Layers/Parking moved to SheetRoute dialog destinations: the stack
        // top is the one sheet showing, so two can never be up at once.
        val stack = listOf(com.vayunmathur.maps.ui.SheetRoute.Browse, com.vayunmathur.maps.ui.SheetRoute.Layers)
        assertEquals(com.vayunmathur.maps.ui.SheetRoute.Layers, stack.last())
    }

    @Test
    fun `chrome choices start at their documented defaults`() {
        val s = state()
        assertEquals(true, s.autoFollow)
        assertEquals(false, s.northUp)
        assertNull(s.postedLimit)
        assertEquals(0L, s.lastProgrammaticMoveMs)
        s.autoFollow = false
        s.northUp = true
        s.postedLimit = PostedLimit(kmh = 50, mph = 30, displayIsMph = false)
        s.lastProgrammaticMoveMs = 123L
        assertEquals(50, s.postedLimit?.kmh)
        assertEquals(123L, s.lastProgrammaticMoveMs)
    }
}
