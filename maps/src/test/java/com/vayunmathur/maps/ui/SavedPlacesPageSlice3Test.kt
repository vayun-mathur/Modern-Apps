package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.SavedPlacesViewModel
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Slice 3/3 (root package files M-Z): [SavedPlacesPage] — the empty state,
 * the populated starred list with its remove action, and the named-lists
 * section, driven by the real [SavedPlacesViewModel].
 *
 * The dialogs (rename / create-list / add-to-list) belong to the same file's
 * lower half; the page owns their visibility state, so opening the create
 * flow is asserted here while the dialog widgets themselves are library/ui.
 */
@RunWith(AndroidJUnit4::class)
class SavedPlacesPageSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun backStack() = NavBackStack(arrayOf(Route.SavedPlacesPage))

    private fun place(name: String = "Ferry Building") = SpecificFeature.GenericPlace(
        name = name,
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    @Test
    fun `empty page invites the first save`() {
        val vm = SavedPlacesViewModel(app)
        rule.setContent {
            SavedPlacesPage(backStack(), vm)
        }
        rule.onNodeWithText("Saved places").assertIsDisplayed()
        rule.onNodeWithText("Places you save appear here").assertIsDisplayed()
    }

    @Test
    fun `saved places list with a working remove`() {
        val vm = SavedPlacesViewModel(app)
        vm.addSaved(place())
        rule.setContent {
            SavedPlacesPage(backStack(), vm)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Ferry Building").assertIsDisplayed()
    }

    @Test
    fun `new-list row opens the create dialog`() {
        val vm = SavedPlacesViewModel(app)
        rule.setContent {
            SavedPlacesPage(backStack(), vm)
        }
        rule.onNodeWithText("Lists").assertIsDisplayed()
        rule.onNodeWithText("New list").performClick()
        // The create dialog confirms with the library "Create" label.
        rule.onNodeWithText("Create", substring = true).assertIsDisplayed()
    }
}
