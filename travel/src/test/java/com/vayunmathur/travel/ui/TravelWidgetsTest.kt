package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Small shared widgets: headers, status box, date fields, range calendar, dropdowns, passenger picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelWidgetsTest {

    @get:Rule val rule = createComposeRule()

    @Test fun sectionHeader_showsText() {
        rule.setContent { SectionHeader("Your trip") }
        rule.onNodeWithText("Your trip").assertIsDisplayed()
    }

    @Test fun statusBox_empty() {
        rule.setContent { StatusBox(loading = false, error = null, isEmpty = true, emptyMessage = "Nothing here") }
        rule.onNodeWithText("Nothing here").assertIsDisplayed()
    }

    @Test fun statusBox_error() {
        rule.setContent { StatusBox(loading = false, error = "Boom", isEmpty = false) }
        rule.onNodeWithText("Boom").assertIsDisplayed()
    }

    @Test fun airlineLogo_blankShowsIata() {
        rule.setContent { AirlineLogo(logoUrl = "", iata = "AS") }
        rule.onNodeWithText("AS").assertIsDisplayed()
    }

    @Test fun dateField_showsLabelAndOpens() {
        var picked: String? = null
        rule.setContent { DateField(label = "Depart", value = "2026-06-24", onDate = { picked = it }) }
        rule.onNodeWithText("Depart").assertIsDisplayed()
        rule.onNodeWithText("Depart").performClick()
        rule.runOnIdle { assertTrue(picked == null) }
    }

    @Test fun dateRangeFields_showsLabels() {
        rule.setContent {
            DateRangeFields(startLabel = "Check-in", endLabel = "Check-out", start = "2026-06-24", end = "2026-06-26", onRange = { _, _ -> })
        }
        rule.onNodeWithText("Check-in").assertIsDisplayed()
        rule.onNodeWithText("Check-out").assertIsDisplayed()
    }

    @Test fun rangeCalendar_rendersTitleAndPicksDay() {
        var range: Pair<LocalDate?, LocalDate?> = null to null
        rule.setContent {
            RangeCalendar(
                title = "Choose dates", startLabel = "Start", endLabel = "End",
                start = null, end = null, onChange = { s, e -> range = s to e },
                minDate = LocalDate.of(2026, 1, 1), allowSameDay = true,
            )
        }
        rule.onNodeWithText("Choose dates").assertIsDisplayed()
    }

    @Test fun searchDropdown_selectsOption() {
        var selected: String? = null
        rule.setContent {
            SearchDropdown(display = "Economy", options = listOf("ECONOMY" to "Economy", "BUSINESS" to "Business"), selectedValue = "ECONOMY", onSelect = { selected = it })
        }
        rule.onNodeWithText("Economy").assertIsDisplayed()
        rule.onNodeWithText("Economy").performClick()
        rule.onNodeWithText("Business").performClick()
        rule.runOnIdle { assertEquals("BUSINESS", selected) }
    }

    @Test fun passengerField_opensDialogAndDone() {
        var done: PassengerMix? = null
        rule.setContent { PassengerField(mix = PassengerMix(adults = 2), onMix = { done = it }) }
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun passengerDialog_doneReturnsMix() {
        var done: PassengerMix? = null
        var cancelled = false
        rule.setContent { PassengerDialog(mix = PassengerMix(adults = 1), onDone = { done = it }, onCancel = { cancelled = true }) }
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle { assertEquals(1, done?.adults); assertTrue(!cancelled) }
    }
}
