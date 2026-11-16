package com.vayunmathur.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.Navigation
import com.vayunmathur.email.Route
import com.vayunmathur.email.addAccountEntries
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun MainContent(viewModel: EmailViewModel) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val navigationRoute = IntentState.navigationRoute
    val isEmlViewer = navigationRoute is Route.EmlViewer

    if (accounts.isEmpty() && !isEmlViewer) {
        // First-run gate: no account yet, so there is no main graph to
        // push the add-account form onto. Host the same two destinations
        // on a gate-local stack instead; saving an account fills
        // `accounts` and swaps this for the main Navigation, so the
        // form's onAccountAdded has nothing to do here. The picker is
        // the gate root and keeps its no-back presentation.
        val gateStack = rememberNavBackStack<Route>(Route.AddAccount)
        MainNavigation(gateStack) {
            addAccountEntries(gateStack, onPickBack = null, onAccountAdded = {})
        }
    } else {
        Navigation(viewModel = viewModel)
    }
}
