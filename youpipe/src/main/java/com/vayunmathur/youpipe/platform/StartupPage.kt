package com.vayunmathur.youpipe.platform

import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route

// --- Default startup page ("Open to" setting) ---

/**
 * The sibling tab route for a legacy tab index (deep-link / default-page
 * `initialTab`): the index now only chooses the start route, never live
 * pager state. Out-of-range indices fall back to Home.
 */
fun tabRoute(initialTab: Int): Route = when (initialTab) {
    1 -> Route.Subscriptions
    2 -> Route.History
    3 -> Route.Saved
    4 -> Route.Settings
    else -> Route.Home
}

/** DataStore key holding the persisted default-page choice. */
const val DEFAULT_PAGE_KEY = "default_page"

const val DEFAULT_PAGE_HOME = "home"
const val DEFAULT_PAGE_SUBSCRIPTIONS = "subscriptions"
const val DEFAULT_PAGE_ALL_SUBSCRIPTIONS = "all_subscriptions"
const val DEFAULT_PAGE_HISTORY = "history"
const val DEFAULT_PAGE_DOWNLOADS = "downloads"
const val DEFAULT_PAGE_SAVED = "saved"
const val DEFAULT_PAGE_SETTINGS = "settings"

/**
 * Ordered options for the "Open to" picker: (persisted key, label string res).
 * Stable string keys are used instead of [Route] objects so the choice
 * serializes cleanly and survives refactors.
 */
val DEFAULT_PAGE_OPTIONS: List<Pair<String, Int>> = listOf(
    DEFAULT_PAGE_HOME to UiR.string.home,
    DEFAULT_PAGE_SUBSCRIPTIONS to R.string.title_subscriptions,
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS to R.string.label_all_subscriptions,
    DEFAULT_PAGE_HISTORY to R.string.title_history,
    DEFAULT_PAGE_SAVED to UiR.string.saved,
    DEFAULT_PAGE_SETTINGS to UiR.string.settings,
)

/** Map a persisted default-page key to the initial backstack to launch with. */
fun defaultBackStack(key: String?): List<Route> = when (key) {
    DEFAULT_PAGE_SUBSCRIPTIONS -> listOf(tabRoute(1))
    // Seed the Subscriptions root beneath the all-subscriptions feed so Back
    // returns to the Subscriptions list instead of exiting the app.
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS ->
        listOf(tabRoute(1), Route.SubscriptionVideosPage(null))
    DEFAULT_PAGE_HISTORY -> listOf(tabRoute(2))
    // Both the legacy "downloads" key and the new "saved" key open the Saved hub.
    DEFAULT_PAGE_DOWNLOADS, DEFAULT_PAGE_SAVED -> listOf(tabRoute(3))
    DEFAULT_PAGE_SETTINGS -> listOf(tabRoute(4))
    else -> listOf(tabRoute(0)) // home / unset / unknown
}
