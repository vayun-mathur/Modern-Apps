package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.ui.dialogs.EventDeleteConfirmDialog
import com.vayunmathur.library.util.NavBackStack

@Composable
internal fun EventDeleteConfirmEntry(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    key: Route.EventDeleteConfirmDialog,
) {
    EventDeleteConfirmDialog(
        key.contactId,
        key.contactName,
        viewModel,
        onConfirm = {
            // After confirming deletion, pop the dialog and the detail page
            backStack.pop()
            backStack.pop()
        },
        onDismiss = {
            // Only close the dialog
            backStack.pop()
        },
    )
}
