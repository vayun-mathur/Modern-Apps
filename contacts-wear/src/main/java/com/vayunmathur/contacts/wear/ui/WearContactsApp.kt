package com.vayunmathur.contacts.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.contacts.wear.Route
import com.vayunmathur.contacts.wear.data.WearSnapshot
import com.vayunmathur.contacts.wear.platform.WearDeviceCapabilities

/** Wear app root: Navigation3 back stack (list -> detail carrying the contact id). */
@Composable
fun WearContactsApp(
    snapshot: WearSnapshot,
    capabilities: WearDeviceCapabilities,
) {
    val backStack = rememberNavBackStack(Route.ContactList)
    var query by remember { mutableStateOf("") }
    MaterialTheme {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<Route.ContactList> {
                    ContactListScreen(
                        snapshot = snapshot,
                        query = query,
                        onQueryChange = { query = it },
                        onContactClick = { backStack.add(Route.ContactDetail(it.id)) },
                    )
                }
                entry<Route.ContactDetail> { route ->
                    val contact = snapshot.contacts.firstOrNull { it.id == route.contactId }
                    if (contact != null) {
                        ContactDetailScreen(contact, capabilities)
                    }
                }
            },
        )
    }
}
