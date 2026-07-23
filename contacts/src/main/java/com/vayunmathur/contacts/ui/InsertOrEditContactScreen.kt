package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.vayunmathur.contacts.util.ContactSorting.groupKey
import com.vayunmathur.contacts.util.ContactSorting.sortedLocale
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconClose

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
    val phone = insertOrEditRoute.phone
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.add_number_to_contact))
                        if (!phone.isNullOrBlank()) {
                            Text(phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) { IconClose() }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onExit) { Text(stringResource(R.string.cancel)) }
                Button(onClick = {
                    backStack.setLast(
                        Route.EditContact(
                            contactId = null,
                            name = insertOrEditRoute.name,
                            phone = phone,
                            email = insertOrEditRoute.email,
                            company = insertOrEditRoute.company,
                            notes = insertOrEditRoute.notes
                        )
                    )
                }) {
                    Text(stringResource(R.string.create_new_contact))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CommonSearchBar(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = stringResource(R.string.search_contacts),
                padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (contacts.isEmpty() && searchQuery.isNotEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.no_contacts_found))
                }
            } else {
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
                                    viewModel = viewModel,
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
