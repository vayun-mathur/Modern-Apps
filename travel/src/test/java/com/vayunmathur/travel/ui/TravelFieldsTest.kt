package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.IdentityDocumentDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Passenger sub-fields directly: profile prefill, identity, loyalty, infant link, chips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelFieldsTest {

    @get:Rule val rule = createComposeRule()

    @Test fun profilePrefillRow_clickFills() {
        var picked: Customer? = null
        val customers = listOf(Customer(id = "c1", email = "a@b.c", givenName = "Jane", familyName = "Doe"))
        rule.setContent { ProfilePrefillRow(profiles = customers, onPick = { picked = it }) }
        rule.onNodeWithText("Jane Doe").performClick()
        rule.runOnIdle { assertEquals("c1", picked?.id) }
    }

    @Test fun identityFields_typing() {
        var doc: IdentityDocumentDto? = null
        rule.setContent { IdentityDocumentFields(doc = null, onChange = { doc = it }) }
        rule.onNodeWithText("Passport number").performTextInput("X123")
        rule.runOnIdle { assertEquals("X123", doc?.uniqueIdentifier) }
        rule.onAllNodesWithText("Issuing country", substring = true)[0].performTextInput("gb")
        rule.runOnIdle { assertEquals("GB", doc?.issuingCountryCode) }
    }

    @Test fun loyaltyFields_airlineDropdown() {
        rule.setContent {
            LoyaltyFields(account = null, airlines = listOf(AirlineDto(name = "Alaska Airlines", iataCode = "AS")), onChange = {})
        }
        rule.onNodeWithText("Frequent flyer (optional)", substring = true).assertIsDisplayed()
    }

    @Test fun infantLinkField_chips() {
        var selected: String? = "unset"
        rule.setContent {
            InfantLinkField(selected = null, infantOptions = listOf("pax_1" to "Jane Doe", "pax_2" to "John Doe"), onSelect = { selected = it })
        }
        rule.onNodeWithText("Jane Doe").performClick()
        rule.runOnIdle { assertEquals("pax_1", selected) }
    }

    @Test fun chipRow_selects() {
        var selected: String? = null
        rule.setContent {
            ChipRow(label = "Title", options = listOf("a" to "Alpha", "b" to "Beta"), selected = "", onSelect = { selected = it })
        }
        rule.onNodeWithText("Beta").performClick()
        rule.runOnIdle { assertEquals("b", selected); assertTrue(true) }
    }

    @Test fun airlineDropdown_showsAirline() {
        rule.setContent {
            AirlineDropdown(airlines = listOf(AirlineDto(name = "Alaska Airlines", iataCode = "AS")), selectedIata = "AS", onSelect = {})
        }
        rule.onAllNodesWithText("Alaska", substring = true)[0].assertExists()
    }
}
