package com.vayunmathur.email.platform

import com.vayunmathur.email.Route
import com.vayunmathur.email.ui.AddAccountFormScreen
import com.vayunmathur.email.ui.AddAccountScreen
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.NavBackStack

/**
 * The add-account destinations, shared by the main graph and the
 * first-run gate in `MainContent` (which hosts them on its own stack
 * before any account exists to navigate with).
 *
 * Picking a provider pushes [Route.AddAccountForm] carrying the provider
 * id; Back from the form is the stack pop back to the picker, and
 * [onAccountAdded] decides where a saved account goes next (pop in the
 * main graph, nothing at the gate — the accounts flow swaps the gate for
 * the graph on its own).
 */
internal fun EntryProviderScope<Route>.addAccountEntries(
    backStack: NavBackStack<Route>,
    onPickBack: (() -> Unit)? = { backStack.pop() },
    onAccountAdded: () -> Unit,
) {
    entry<Route.AddAccount>(metadata = ListDetailPage()) {
        AddAccountScreen(
            onBack = onPickBack,
            onPickProvider = { providerId -> backStack.add(Route.AddAccountForm(providerId)) },
        )
    }
    entry<Route.AddAccountForm>(metadata = ListDetailPage()) { route ->
        AddAccountFormScreen(
            providerId = route.providerId,
            onBack = { backStack.pop() },
            onAccountAdded = onAccountAdded,
        )
    }
}
