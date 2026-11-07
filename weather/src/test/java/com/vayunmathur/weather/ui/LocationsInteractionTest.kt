package com.vayunmathur.weather.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.platform.LocationRow
import com.vayunmathur.weather.platform.LocationsUiState
import com.vayunmathur.weather.platform.WeatherActions
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LocationsInteractionTest {

    @get:Rule val rule = createComposeRule()

    private fun loc(id: Long, name: String) = SavedLocation(
        id = id, name = name, country = "Germany", latitude = 52.5, longitude = 13.4,
    )

    @Test fun longPressOpensDeleteSheetAndConfirms() {
        var deleted: SavedLocation? = null
        val actions = object : WeatherActions {
            override fun deleteLocation(location: SavedLocation) { deleted = location }
        }
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(
                    rows = listOf(LocationRow(loc(1, "Berlin"), "No data yet", 2, true)),
                    activeLocationId = 1L,
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("Berlin").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Confirm delete").assertIsDisplayed()
        rule.onNodeWithText("Confirm delete").performClick()
        assertEquals("Berlin", deleted?.name)
    }

    @Test fun closeAndUnitsButtonsRender() {
        var closed = false
        rule.setContent {
            LocationsScreen(
                state = LocationsUiState(rows = emptyList()),
                actions = WeatherActions.Noop,
                onClose = { closed = true },
            )
        }
        rule.onNodeWithText("Units").assertIsDisplayed()
        assertTrue(!closed)
    }
}
