package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The false-empty-state bug, encoded as a test.
 *
 * The search page used to decide what to draw from `query.length >= 2 && results.isEmpty()`,
 * which is equally true while a search is running and after one found nothing. So for the whole
 * debounce plus the offline lookup plus the network round-trip it told the user "No results
 * found" about a search that was still going, then replaced it with the results.
 *
 * [SearchUiState.phase] is where that distinction now lives, so this is where it gets defended.
 */
class SearchUiStateTest {

    private val results = listOf(
        SearchResult("1", "Ferry Building", null, 37.7955, -122.3933, null),
    )

    /** The regression. A running search must never look like a finished empty one. */
    @Test
    fun `a running search with no results yet is Searching, not Empty`() {
        val state = SearchUiState(query = "ferry", results = emptyList(), searching = true)
        assertEquals(SearchPhase.Searching, state.phase)
    }

    @Test
    fun `a finished search with no results is Empty`() {
        val state = SearchUiState(query = "ferry", results = emptyList(), searching = false)
        assertEquals(SearchPhase.Empty, state.phase)
    }

    @Test
    fun `a finished search with results is Results`() {
        val state = SearchUiState(query = "ferry", results = results, searching = false)
        assertEquals(SearchPhase.Results, state.phase)
    }

    /**
     * Stale results from the previous query stay on screen while the next one runs. Blanking the
     * list to a spinner on every keystroke made the results blink per character and re-keyed the
     * sheet's height each time; the new rows simply replace the old ones when they arrive.
     */
    @Test
    fun `stale results stay listed while the next search runs`() {
        val state = SearchUiState(query = "ferry b", results = results, searching = true)
        assertEquals(SearchPhase.Results, state.phase)
    }

    /** Below the two-character floor nothing is searched, so recents are the useful thing. */
    @Test
    fun `a short query offers recents when there are any`() {
        val state = SearchUiState(query = "f", recents = listOf("ferry"))
        assertEquals(SearchPhase.Recents, state.phase)
    }

    @Test
    fun `a short query with no recents is Idle`() {
        assertEquals(SearchPhase.Idle, SearchUiState(query = "f").phase)
    }

    @Test
    fun `the initial state is Idle`() {
        assertEquals(SearchPhase.Idle, SearchUiState().phase)
    }

    /**
     * The two-character floor is the ViewModel's, and the phase has to agree with it: at one
     * character nothing has been asked for, so "no results" would be a lie.
     */
    @Test
    fun `the search floor is two characters`() {
        assertEquals(SearchPhase.Idle, SearchUiState(query = "f", searching = false).phase)
        assertEquals(SearchPhase.Empty, SearchUiState(query = "fe", searching = false).phase)
    }

    /** A short query cannot be searching, even if the flag says so mid-cancellation. */
    @Test
    fun `clearing the query back below the floor leaves the search phases`() {
        val state = SearchUiState(query = "f", results = results, searching = true)
        assertEquals(SearchPhase.Idle, state.phase)
    }
}
