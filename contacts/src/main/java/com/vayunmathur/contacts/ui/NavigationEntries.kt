package com.vayunmathur.contacts.ui

import com.vayunmathur.contacts.Route

/**
 * The tab a deep link starts on: App Info and Settings links start on
 * Settings, a groups link on Groups, anything else on the Contacts home tab.
 */
private fun startTabFor(initialRoute: Route?, launchedFromAppInfo: Boolean): Route = when {
    launchedFromAppInfo || initialRoute is Route.Settings -> Route.Settings
    initialRoute is Route.GroupsList -> Route.GroupsList()
    initialRoute is Route.ContactsList -> Route.ContactsList
    (initialRoute as? Route.GroupsList)?.expandGroupId != null -> Route.GroupsList()
    else -> Route.ContactsList
}

/**
 * The root route for [initialRoute]: tab routes start on their sibling tab,
 * while non-tab deep links (detail/edit/import) keep their previous shape —
 * the route itself is the root, with the bottom bar showing no tab selected.
 */
internal fun resolveStartRoute(initialRoute: Route?, launchedFromAppInfo: Boolean): Route =
    when (initialRoute) {
        is Route.ContactsList, is Route.GroupsList, is Route.Settings, null ->
            startTabFor(initialRoute, launchedFromAppInfo)
        else -> initialRoute
    }
