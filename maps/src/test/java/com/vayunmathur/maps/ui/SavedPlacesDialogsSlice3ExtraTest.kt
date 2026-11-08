package com.vayunmathur.maps.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SavedPlaceStore
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Slice 3/3 extra: SavedPlacesPage dialogs and PlaceSheetHeader VM overloads. */
@RunWith(AndroidJUnit4::class)
class SavedPlacesDialogsSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun clearSaved() {
        val store = SavedPlaceStore(DataStoreUtils.getInstance(app))
        runBlocking { store.setSaved(emptyList()); store.setLists(emptyMap()) }
    }

    private fun backStack() = NavBackStack<Route>(arrayOf(Route.SavedPlacesPage))

    private fun place(name: String = "Ferry Building") = SpecificFeature.GenericPlace(
        name = name, phone = null, website = null, openingHours = null, position = GeoPoint(-122.4, 37.8),
    )

    private suspend fun pumpVm(vm: SavedPlacesViewModel) {
        // VM work is on viewModelScope (paused Main): run tasks until flows settle
        withTimeout(10000) {
            while (vm.saved.value.isEmpty() && vm.lists.value.isEmpty()) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask()
                delay(25)
            }
        }
    }

    private fun awaitNode(text: String, substring: Boolean = false) {
        rule.waitUntil(timeoutMillis = 10000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask()
            rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Open the overflow menu of the saved row named [name] by trying right-side click nodes. */
    private fun openRowMenu(name: String) {
        val row = rule.onAllNodes(hasText(name)).fetchSemanticsNodes().first()
        val rowBounds = row.boundsInRoot
        val clicks = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        val candidates = clicks.withIndex().filter { (_, n) ->
            n.boundsInRoot.left > rowBounds.center.x && n.boundsInRoot.top < rowBounds.bottom + 80
        }
        for ((idx, _) in candidates) {
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[idx].performClick() }
            rule.waitForIdle()
            if (rule.onAllNodes(hasText("Rename")).fetchSemanticsNodes().isNotEmpty()) return
            if (rule.onAllNodes(hasText("Add to list")).fetchSemanticsNodes().isNotEmpty()) return
        }
        error("overflow menu for $name never opened; clicks=${clicks.map { it.boundsInRoot }}")
    }

    @Test fun renameSavedPlaceViaDialog() { runBlocking {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        pumpVm(vm)
        rule.setContent { SavedPlacesPage(backStack(), vm) }
        awaitNode("Ferry Building")
        openRowMenu("Ferry Building")
        rule.onNodeWithText("Rename").performClick()
        // Dialog is pre-filled with the current name; replace it
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Renamed Pier")
        rule.onNodeWithText("Rename").performClick()
        withTimeout(10000) { while (vm.saved.value.firstOrNull()?.name != "Renamed Pier") { Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask(); delay(25) } }
        assertEquals("Renamed Pier", vm.saved.value.single().name)
        Unit
    } }

    @Test fun addToListAndCreateListAndDeleteListFlows() { runBlocking {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        pumpVm(vm)
        rule.setContent { SavedPlacesPage(backStack(), vm) }
        awaitNode("Ferry Building")
        openRowMenu("Ferry Building")
        rule.onNodeWithText("Add to list", substring = true).performClick()
        // Create a new list from inside the add-to-list dialog
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Weekend")
        rule.onNodeWithText("Create").performClick()
        withTimeout(10000) { while (vm.lists.value["Weekend"]?.size != 1) { Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask(); delay(25) } }
        assertEquals(1, vm.lists.value.getValue("Weekend").size)
        // New list via the section row
        rule.onNodeWithText("New list").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Work")
        rule.onNodeWithText("Create").performClick()
        withTimeout(10000) { while (!vm.lists.value.containsKey("Work")) { Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask(); delay(25) } }
        assertTrue(vm.lists.value.containsKey("Work"))
        Unit
    } }

    @Test fun removeSavedPlaceViaMenu() { runBlocking {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        pumpVm(vm)
        rule.setContent { SavedPlacesPage(backStack(), vm) }
        awaitNode("Ferry Building")
        openRowMenu("Ferry Building")
        rule.onNodeWithText("Remove").performClick()
        withTimeout(10000) { while (vm.saved.value.isNotEmpty()) { Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask(); delay(25) } }
        assertTrue(vm.saved.value.isEmpty())
        Unit
    } }

    @Test fun placeSheetHeaderVmOverloads() {
        val vm = SelectedFeatureViewModel(app)
        val savedVm = SavedPlacesViewModel(app)
        var directions = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PlaceSheetHeader(
                    viewModel = vm, savedPlacesViewModel = savedVm, inactiveNavigation = null,
                    feature = SpecificFeature.Restaurant("Pizza Place", null, null, "https://example.com/menu", null, GeoPoint(-122.4, 37.8)),
                    requestDirections = { directions++ },
                )
                PlaceSheetHeader(
                    viewModel = vm, savedPlacesViewModel = savedVm, inactiveNavigation = null,
                    feature = place("Blue Bottle"),
                    requestDirections = { directions++ },
                )
            }
        }
        rule.onNodeWithText("Pizza Place").assertIsDisplayed()
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.waitForIdle()
    }
}
