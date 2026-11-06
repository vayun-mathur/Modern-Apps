package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.util.StayResultsActions
import com.vayunmathur.travel.util.StaySearchState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stateless [StayResultsScreen] under the Compose rule: loading / error / empty states,
 * stay cards with price + rating labels, and click-through. Amenities join and the star
 * label helper are asserted through the rendered card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelStayResultsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun stay(
        id: String,
        name: String = "Grand Plaza",
        amount: String = "189.00",
        rating: Long = 4,
        score: Double = 8.7,
    ) = StaySearchResultDto(
        id = id, name = name, rating = rating, reviewScore = score,
        address = "100 Main St",
        amenities = listOf("WiFi", "Pool", "Gym", "Spa", "Bar"),
        cheapestAmount = amount, cheapestCurrency = "USD",
    )

    @Test
    fun emptySearch_showsEmptyMessage() {
        rule.setContent {
            StayResultsScreen(
                place = "Paris",
                state = StaySearchState(hasSearched = true),
                actions = StayResultsActions.Noop,
            )
        }
        rule.onNodeWithText("No hotels found for those dates.").assertIsDisplayed()
    }

    @Test
    fun error_showsMessage() {
        rule.setContent {
            StayResultsScreen(
                place = "Paris",
                state = StaySearchState(hasSearched = true, error = "Stays failed"),
                actions = StayResultsActions.Noop,
            )
        }
        rule.onNodeWithText("Stays failed").assertIsDisplayed()
    }

    @Test
    fun stays_renderNamePriceAndRating() {
        rule.setContent {
            StayResultsScreen(
                place = "Paris",
                state = StaySearchState(
                    hasSearched = true,
                    results = listOf(stay("s1"), stay("s2", name = "Riverside Inn", amount = "99")),
                ),
                actions = StayResultsActions.Noop,
            )
        }
        rule.onNodeWithText("Grand Plaza").assertIsDisplayed()
        rule.onNodeWithText("Riverside Inn").assertIsDisplayed()
        rule.onNodeWithText("$189").assertIsDisplayed()
        rule.onNodeWithText("$99").assertIsDisplayed()
        // starLabel(4, 8.7) = "★★★★ · 8.7/10", rendered on both cards.
        assertEquals(
            2,
            rule.onAllNodesWithText("★★★★ · 8.7/10").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun amenities_showFirstFourJoined() {
        rule.setContent {
            StayResultsScreen(
                place = "Paris",
                state = StaySearchState(hasSearched = true, results = listOf(stay("s1"))),
                actions = StayResultsActions.Noop,
            )
        }
        rule.onNodeWithText("WiFi · Pool · Gym · Spa").assertIsDisplayed()
    }

    @Test
    fun stayClick_opensStay() {
        val opened = mutableListOf<String>()
        rule.setContent {
            StayResultsScreen(
                place = "Paris",
                state = StaySearchState(hasSearched = true, results = listOf(stay("s1"))),
                actions = object : StayResultsActions {
                    override fun openStay(result: StaySearchResultDto) {
                        opened.add(result.id)
                    }
                },
            )
        }
        rule.onAllNodes(hasText("Grand Plaza") and hasClickAction())[0].performClick()
        rule.runOnIdle { assertEquals(listOf("s1"), opened) }
    }

    @Test
    fun starLabel_edges() {
        // Direct unit coverage for the internal helper through the same package.
        assertEquals("", starLabel(0, 0.0))
        assertEquals("★★★★★", starLabel(5, 0.0))
        assertEquals("9.1/10", starLabel(0, 9.05))
        assertEquals("", starLabel(9, 0.0))
    }
}
