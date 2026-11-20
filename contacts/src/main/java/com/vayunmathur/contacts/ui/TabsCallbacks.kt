package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.util.NavBackStack

/** True for a non-tab deep-link that is not already on top of [backStack]. */
internal fun Route?.shouldPushOnto(backStack: NavBackStack<Route>): Boolean {
    if (this == null) return false
    if (this is Route.ContactsList || this is Route.GroupsList || this is Route.Settings) {
        return false
    }
    return backStack.last() != this
}

internal class TabsCallbacks(
    val onContactClick: (com.vayunmathur.contacts.data.Contact) -> Unit,
    val onAddContactClick: () -> Unit,
)

@Composable
internal fun rememberTabsCallbacks(
    backStack: NavBackStack<Route>,
): TabsCallbacks = remember(backStack) {
    TabsCallbacks(
        onContactClick = { contact ->
            // An open detail or editor is replaced, not stacked: on two-pane the list
            // stays beside it, so a second tap must not pile detail on detail.
            backStack.pushOrReplace(Route.ContactDetail(contact.id)) {
                it is Route.ContactDetail || it is Route.EditContact
            }
        },
        onAddContactClick = {
            if (backStack.last() is Route.ContactDetail) {
                backStack.pop()
            }
            backStack.add(Route.EditContact(null))
        },
    )
}
