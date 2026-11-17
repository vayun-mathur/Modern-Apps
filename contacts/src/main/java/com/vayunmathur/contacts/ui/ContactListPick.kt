package com.vayunmathur.contacts.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.contacts.domain.ContactSorting.groupKey
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListPick(
    mimeType: String?,
    contacts: List<Contact>,
    allowMultiple: Boolean = false,
    selectedUris: List<Uri> = emptyList(),
    onConfirm: () -> Unit = {},
    isLoading: Boolean = true,
    onClick: (Uri) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else {
            val q = query.trim().lowercase()
            contacts.filter { contact ->
                contact.name.value.lowercase().contains(q) ||
                    contact.details.phoneNumbers.any { it.number.contains(query.trim()) } ||
                    contact.details.emails.any { it.address.lowercase().contains(q) }
            }
        }
    }
    val (favorites, otherContacts) = remember(filtered) { filtered.partition { it.isFavorite } }

    val groupedContacts = remember(otherContacts) {
        otherContacts
            .groupBy { groupKey(it.name.value) }
            .toSortedMap()
    }

    val selectedSet = selectedUris.toSet()

    LazyListScaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (allowMultiple) {
                ExtendedFloatingActionButton(onClick = onConfirm) {
                    val label = stringResource(UiR.string.done) +
                        if (selectedUris.isNotEmpty()) " (${selectedUris.size})" else ""
                    Text(label)
                }
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item(key = "pick-search") {
            CommonSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search_contacts),
                padding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        when {
            filtered.isEmpty() && isLoading -> {
                item(key = "pick-loading") {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            filtered.isEmpty() -> {
                item(key = "pick-empty") {
                    if (query.isNotEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.no_contacts_found),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    } else {
                        EmptyState(
                            title = stringResource(R.string.no_contacts_yet),
                            modifier = Modifier.fillParentMaxSize(),
                            message = stringResource(R.string.no_contacts_yet_message),
                        )
                    }
                }
            }
            else -> {
                if (favorites.isNotEmpty()) {
                    item(key = "pick-favorites-header") { FavoritesHeader() }
                    item(key = "pick-favorites-card") {
                        GroupedContactSection(count = favorites.size) { idx ->
                            ContactItemPick(favorites[idx], mimeType, selectedSet, onClick)
                        }
                    }
                }

                groupedContacts.forEach { (letter, contactsInGroup) ->
                    item(key = "pick-letter-header-$letter") { LetterHeader(letter) }
                    item(key = "pick-letter-card-$letter") {
                        GroupedContactSection(count = contactsInGroup.size) { idx ->
                            ContactItemPick(contactsInGroup[idx], mimeType, selectedSet, onClick)
                        }
                    }
                }
            }
        }
    }
}
