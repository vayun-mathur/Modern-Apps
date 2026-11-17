package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.ui.dialogs.AddAccountDialog
import com.vayunmathur.contacts.ui.dialogs.AddToGroupDialog
import com.vayunmathur.contacts.ui.dialogs.EventDatePickerDialog
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage

@Composable
internal fun NavGraphEntries(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    tabsCallbacks: TabsCallbacks,
    groupsExpandId: Long?,
    goBack: () -> Unit,
) {
    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf<RouteTab<Route>>(
            RouteTab(Route.ContactsList, stringResource(UiR.string.contacts)) { IconPerson() },
            RouteTab(Route.GroupsList(), stringResource(R.string.groups)) { IconGroup() },
            RouteTab(Route.Settings, stringResource(com.vayunmathur.library.ui.R.string.settings)) { IconSettings() },
        ),
    ) {
    MainNavigation(backStack) {
        entry<Route.ContactsList>(metadata = ListPage() + SiblingPage()) {
            ContactList(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = tabsCallbacks.onContactClick,
                onAddContactClick = tabsCallbacks.onAddContactClick,
            )
        }
        entry<Route.GroupsList>(metadata = ListPage() + SiblingPage()) { key ->
            GroupsPage(viewModel, backStack, key.expandGroupId ?: groupsExpandId)
        }
        entry<Route.Settings>(metadata = ListPage() + SiblingPage()) {
            SettingsPage(viewModel, backStack)
        }

        entry<Route.ContactDetail>(metadata = ListDetailPage() + MorphPage()) { key ->
            ContactDetailsPage(
                viewModel = viewModel,
                contactId = key.contactId,
                onBack = { goBack() },
                onEdit = { id -> backStack.add(Route.EditContact(id)) },
                onDelete = {
                    // Show the delete confirmation dialog using the contact id and name
                    val contact = viewModel.getContact(key.contactId)
                    backStack.add(
                        Route.EventDeleteConfirmDialog(key.contactId, contact?.name?.value),
                    )
                },
                showBackButton = true,
            )
        }
        entry<Route.EditContact>(metadata = ListDetailPage() + MorphPage()) { key ->
            EditContactPage(backStack, viewModel, key, onExit = { goBack() })
        }

        entry<Route.InsertOrEditContact>(metadata = ListDetailPage()) { key ->
            InsertOrEditContactScreen(
                viewModel = viewModel,
                backStack = backStack,
                insertOrEditRoute = key,
                onExit = goBack,
            )
        }

        entry<Route.AddAccountDialog>(metadata = DialogPage()) {
            AddAccountDialog(viewModel) { backStack.pop() }
        }

        entry<Route.EventDatePickerDialog>(metadata = DialogPage()) { key ->
            EventDatePickerDialog(key.id, key.initialDate) { backStack.pop() }
        }

        entry<Route.EventDeleteConfirmDialog>(metadata = DialogPage()) { key ->
            EventDeleteConfirmEntry(viewModel, backStack, key)
        }

        entry<Route.AddToGroupDialog>(metadata = DialogPage()) { key ->
            AddToGroupDialog(viewModel, key.contactIds) { backStack.pop() }
        }

        entry<Route.CropPhoto>(metadata = ListDetailPage()) { key ->
            CropPhotoEntry(backStack, viewModel, key)
        }

        entry<Route.ImportVcf> { key ->
            ImportVcfScreen(viewModel, backStack, key.uris)
        }
    }
    }
}
