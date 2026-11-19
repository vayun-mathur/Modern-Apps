package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.domain.ContactSorting.groupKey
import com.vayunmathur.contacts.domain.ContactSorting.sortedLocale
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.addPhoneNumberToContact
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * Shown for ACTION_INSERT_OR_EDIT / SHOW_OR_CREATE_CONTACT (Fossify dialer "Add number to contact").
 * Lets the user:
 *  - Pick an existing contact → appends the phone number to that contact
 *  - Create new contact → navigates to EditContact with the number prefilled
 */
@Composable
fun InsertOrEditContactScreen(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    insertOrEditRoute: Route.InsertOrEditContact,
    onExit: () -> Unit
) {
    val phone = insertOrEditRoute.prefill?.primaryPhone
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hasLoadedContacts by viewModel.hasLoadedContacts.collectAsStateWithLifecycle()
    var isSaving by remember { mutableStateOf(false) }

    // Cold-launched via external intent (dialer "Add to contacts"), _allContacts starts
    // empty and was previously loaded only after a 500ms debounce, so the UI rendered
    // an empty LazyColumn that looked like "empty contact list". Trigger a load and
    // show a spinner while waiting.
    LaunchedEffect(Unit) {
        viewModel.loadContacts()
    }

    AppScaffold(
        title = {
            Column {
                Text(stringResource(R.string.add_number_to_contact))
                if (!phone.isNullOrBlank()) {
                    Text(phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        onClose = onExit,
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onExit) { Text(stringResource(UiR.string.cancel)) }
                Button(onClick = {
                    backStack.setLast(
                        Route.EditContact(
                            contactId = null,
                            prefill = insertOrEditRoute.prefill
                        )
                    )
                }) {
                    Text(stringResource(R.string.create_new_contact))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CommonSearchBar(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = stringResource(R.string.search_contacts),
                padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            )

            when {
                contacts.isEmpty() && searchQuery.isEmpty() && !hasLoadedContacts -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                contacts.isEmpty() && searchQuery.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(stringResource(R.string.no_contacts_yet), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            backStack.setLast(
                                Route.EditContact(
                                    contactId = null,
                                    prefill = insertOrEditRoute.prefill
                                )
                            )
                        }) {
                            Text(stringResource(R.string.create_new_contact))
                        }
                    }
                }
                contacts.isEmpty() && searchQuery.isNotEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(stringResource(R.string.no_contacts_found))
                    }
                }
                else -> {
                    val grouped = remember(contacts) {
                        contacts.groupBy { groupKey(it.name.value) }
                            .mapValues { (_, c) -> c.sortedLocale() }
                            .toSortedMap()
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        grouped.forEach { (letter, contactsInGroup) ->
                            item(key = "insedit-header-$letter") { LetterHeader(letter) }
                            item(key = "insedit-card-$letter") {
                                GroupedContactSection(count = contactsInGroup.size) { idx ->
                                    val contact = contactsInGroup[idx]
                                    ContactItem(
                                        contact = contact,
                                        isSelected = false,
                                        showAccountLabels = true,
                                        allGroups = groups,
                                        decodePhoto = viewModel::decodePhoto,
                                        embeddedInCard = true,
                                        onClick = {
                                            if (isSaving) return@ContactItem
                                            if (phone.isNullOrBlank()) {
                                                backStack.setLast(Route.ContactDetail(contact.id))
                                                return@ContactItem
                                            }
                                            isSaving = true
                                            viewModel.addPhoneNumberToContact(contact.id, phone) {
                                                isSaving = false
                                                backStack.setLast(Route.ContactDetail(contact.id))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
