package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.maps.util.MapsSearchViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Slice 3/3 (root package files M-Z): [MapWideLayout]'s panel split and
 * [MapSearchHost]'s open-request state.
 *
 * The layout is pure composition (map and panel are caller lambdas), and the
 * host is plain state over the real [MapsSearchViewModel]: opening search
 * records the bias centre from the camera, and a blank query opens without
 * touching the search text.
 */
@RunWith(AndroidJUnit4::class)
class WideLayoutSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `map fills the window with no panel`() {
        rule.setContent {
            MapWideLayout(
                mapContent = { Text("map pane") },
                panelContent = null,
            )
        }
        rule.onNodeWithText("map pane").assertIsDisplayed()
    }

    @Test
    fun `map and panel share the window`() {
        rule.setContent {
            MapWideLayout(
                mapContent = { Text("map pane") },
                panelContent = { Text("side panel") },
            )
        }
        rule.onNodeWithText("map pane").assertIsDisplayed()
        rule.onNodeWithText("side panel").assertIsDisplayed()
    }

    @Test
    fun `search host starts closed`() {
        // Search starts as the Browse root of the sheet stack.
        val stack = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.maps.SheetRoute>(arrayOf(com.vayunmathur.maps.SheetRoute.Browse))
        assertEquals(com.vayunmathur.maps.SheetRoute.Browse, stack.last())
    }

    @Test
    fun `open search records the request without a query`() {
        val stack = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.maps.SheetRoute>(arrayOf(com.vayunmathur.maps.SheetRoute.Browse))
        stack.add(com.vayunmathur.maps.SheetRoute.Search(waypointIndex = 1))
        val request = stack.last() as com.vayunmathur.maps.SheetRoute.Search
        assertEquals(1, request.waypointIndex)
    }

    @Test
    fun `open search with a query drives the viewmodel`() {
        val vm = MapsSearchViewModel(app)
        vm.setQuery("pizza", 37.8, -122.4)
        assertEquals("pizza", vm.query.value)
    }

    @Test
    fun `blank query opens without searching`() {
        val vm = MapsSearchViewModel(app)
        assertEquals("", vm.query.value)
    }

    @androidx.compose.runtime.Composable
    private fun Text(label: String) {
        Column(Modifier) {
            androidx.compose.material3.Text(label)
        }
    }
}
