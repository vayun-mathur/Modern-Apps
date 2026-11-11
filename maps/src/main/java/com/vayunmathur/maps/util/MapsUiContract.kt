package com.vayunmathur.maps.util

/**
 * The UI contract between the ViewModels and the screens that can be rendered without a
 * map.
 *
 * A screen takes a state value plus an actions interface rather than the ViewModels
 * themselves, so it can be rendered by a `@Preview` — which is what the store listing
 * images are generated from. Anything drawn on top of [VectorMap][com.vayunmathur.library.map.VectorMap]
 * stays as it is; the map is a native surface that a preview cannot render.
 *
 * This lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`.
 */

/** Everything the search screen draws. */
data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val recents: List<String> = emptyList(),
    /** Whether a search is running. See [SearchPhase]. */
    val searching: Boolean = false,
) {
    /**
     * What the results area should show.
     *
     * Derived here rather than at the call site because the view used to work it out from
     * `query.length >= 2 && results.isEmpty()`, which is equally true while a search is running
     * and after it found nothing — so a running search rendered "No results found". Naming the
     * phases makes the distinction impossible to collapse again, and makes the ordering
     * (searching wins over empty) a property of the state instead of the order of `when` branches.
     */
    val phase: SearchPhase
        get() = when {
            query.length < 2 && recents.isNotEmpty() -> SearchPhase.Recents
            query.length < 2 -> SearchPhase.Idle
            searching -> SearchPhase.Searching
            results.isEmpty() -> SearchPhase.Empty
            else -> SearchPhase.Results
        }
}

/** The five things the search results area can be showing. */
enum class SearchPhase {
    /** Nothing typed and nothing to suggest. */
    Idle,

    /** Nothing typed, but there are recent queries worth offering. */
    Recents,

    /** A search is in flight — including its debounce window. */
    Searching,

    /** The search finished and found nothing. */
    Empty,

    /** The search finished and found something. */
    Results,
}

/**
 * Search callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * These are not implemented by [MapsSearchViewModel]: a query needs the visible bounding
 * box (to rank the offline index search toward the map centre) and picking a result touches the
 * selection the map screen owns, so the map screen builds the adapter itself.
 */
interface SearchActions {
    fun setQuery(query: String) {}
    fun selectResult(result: SearchResult) {}
    /**
     * Handle a postal address picked from a contact (P17): run the offline index
     * search for [address] and auto-select the first result — the same path as tapping
     * the top search row, no user tap required. No-op when the search is empty.
     */
    fun pickContactAddress(address: String) {}
    fun clearRecents() {}
    fun back() {}

    companion object {
        val Noop: SearchActions = object : SearchActions {}
    }
}
