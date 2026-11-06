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
import kotlin.test.assertNull
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
        val vm = MapsSearchViewModel(app)
        val host = MapSearchHost(vm, CameraState())
        assertNull(host.searchRequest)
        assertEquals(false, host.searchWasOpen)
    }

    @Test
    fun `open search records the request without a query`() {
        val vm = MapsSearchViewModel(app)
        val host = MapSearchHost(vm, CameraState())
        host.openSearch(waypointIndex = 1)
        val request = host.searchRequest
        assertTrue(request != null)
        assertEquals(1, request.waypointIndex)
        assertEquals("", vm.query.value)
    }

    @Test
    fun `open search with a query drives the viewmodel`() {
        val vm = MapsSearchViewModel(app)
        val host = MapSearchHost(vm, CameraState())
        host.openSearch("pizza")
        assertEquals("pizza", vm.query.value)
        assertTrue(host.searchRequest != null)
    }

    @Test
    fun `blank query opens without searching`() {
        val vm = MapsSearchViewModel(app)
        val host = MapSearchHost(vm, CameraState())
        host.openSearch("   ")
        assertEquals("", vm.query.value)
        assertTrue(host.searchRequest != null)
    }

    @androidx.compose.runtime.Composable
    private fun Text(label: String) {
        Column(Modifier) {
            androidx.compose.material3.Text(label)
        }
    }
}
