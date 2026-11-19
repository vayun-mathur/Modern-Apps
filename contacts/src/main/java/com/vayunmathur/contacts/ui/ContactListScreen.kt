package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.platform.ContactListUiState
import com.vayunmathur.contacts.domain.ContactSorting.ContactSortMode
import com.vayunmathur.contacts.domain.ContactSorting.sectionKey
import com.vayunmathur.contacts.domain.ContactSorting.sortedByMode
import com.vayunmathur.contacts.platform.ContactsActions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconMove
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.PopVisibility
import com.vayunmathur.library.ui.SwappedTopBar
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion

/**
 * The contact list, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    state: ContactListUiState,
    actions: ContactsActions,
    sortMode: ContactSortMode = ContactSortMode.FirstName,
    onSortModeChange: (ContactSortMode) -> Unit = {},
) {
    val contacts = state.contacts
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode = selectedIds.isNotEmpty()

    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }
    val groupedContacts = remember(otherContacts, sortMode) {
        otherContacts.groupBy { it.sectionKey(sortMode) }
            .mapValues { (_, c) -> c.sortedByMode(sortMode) }
            .toSortedMap()
    }
    // Flat item index of each section header: favorites, then one per letter,
    // in the same order the list emits them (0 is the empty-state/loading slot).
    val sectionIndex = remember(favorites.isNotEmpty(), groupedContacts) {
        buildMap {
            var i = 1
            if (favorites.isNotEmpty()) {
                put(FAVORITES_KEY, 0)
                i += 1 + favorites.size
            }
            for (letter in groupedContacts.keys) {
                put(letter.toString(), i)
                i += 1 + (groupedContacts[letter]?.size ?: 0)
            }
        }
    }

    val toggleSelection = { id: Long ->
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    var isFocusableBySystem by remember { mutableStateOf(false) }

    // Hoisted so the section rail below tracks and drives the same list.
    val listState = rememberLazyListState()

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_selected_contacts_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_selected_contacts_confirm, selectedIds.size, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = contacts.filter { it.id in selectedIds }
                    toDelete.forEach { actions.deleteContact(it) }
                    selectedIds.clear()
                    showDeleteConfirmation = false
                }) {
                    Text(stringResource(UiR.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }

    androidx.activity.compose.BackHandler(enabled = state.searchQuery.isNotEmpty() && !isSelectionMode) {
        actions.setSearchQuery("")
    }

    androidx.activity.compose.BackHandler(enabled = isSelectionMode) {
        selectedIds.clear()
    }

    LazyListScaffold(
        state = listState,
        topBar = {
            SwappedTopBar(showingOverlay = isSelectionMode) { selecting ->
                if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, selectedIds.size, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            actions.addToGroup(selectedIds.toList())
                        }) {
                            IconGroup()
                        }
                        IconButton(onClick = {
                            actions.moveContacts(selectedIds.toList())
                        }) {
                            IconMove()
                        }
                        IconButton(onClick = {
                            actions.shareContacts(contacts.filter { it.id in selectedIds }, "selected_contacts.vcf")
                        }) {
                            IconShare()
                        }
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            IconDelete()
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        CommonSearchBar(
                            value = state.searchQuery,
                            onValueChange = { actions.setSearchQuery(it) },
                            placeholder = stringResource(R.string.search_contacts),
                            padding = PaddingValues(bottom = 8.dp),
                            modifier = Modifier
                                .focusProperties { canFocus = isFocusableBySystem }
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            if (!isFocusableBySystem) isFocusableBySystem = true
                                        }
                                    }
                                }
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        isFocusableBySystem = false
                                    }
                                }
                        )
                    },
                    actions = {
                        IconButton(onClick = { actions.shareContacts(contacts, "all_contacts.vcf") }) {
                            IconShare()
                        }
                        OverflowMenu {
                            Item(
                                text = stringResource(R.string.first_name),
                                trailingIcon = if (sortMode == ContactSortMode.FirstName) {
                                    { IconCheck() }
                                } else null,
                                onClick = { onSortModeChange(ContactSortMode.FirstName) },
                            )
                            Item(
                                text = stringResource(R.string.last_name),
                                trailingIcon = if (sortMode == ContactSortMode.LastName) {
                                    { IconCheck() }
                                } else null,
                                onClick = { onSortModeChange(ContactSortMode.LastName) },
                            )
                        }
                    }
                )
                }
            }
        },
        floatingActionButton = {
            // Scales away when selection mode takes over the bar, rather than vanishing. It does not
            // react to scrolling: the add button should always be reachable.
            PopVisibility(visible = state.showAddButton && !isSelectionMode) {
                FloatingActionButton(onClick = { actions.addContact() }) {
                    IconAdd()
                }
            }
        },
        horizontalPadding = 16.dp,
        // 2dp is the gap *within* a grouped card, now that each contact is its own lazy item rather
        // than a row inside one item per letter. The headers carry the extra 6dp that keeps the gap
        // between groups at the 16dp it has always been.
        verticalArrangement = Arrangement.spacedBy(2.dp),
        overlay = {
            val itemCount by remember { derivedStateOf { listState.layoutInfo.totalItemsCount } }
            ContactSectionScroller(
                state = listState,
                sectionIndex = sectionIndex,
                itemCount = itemCount,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        },
    ) {
        if (contacts.isEmpty()) {
            item(key = "contacts-empty") {
                when {
                    state.searchQuery.isNotEmpty() -> EmptyState(
                        title = stringResource(R.string.no_contacts_found),
                        modifier = Modifier.fillParentMaxSize(),
                    )
                    state.isLoading -> LoadingState(modifier = Modifier.fillParentMaxSize())
                    else -> EmptyState(
                        title = stringResource(R.string.no_contacts_yet),
                        modifier = Modifier.fillParentMaxSize(),
                        message = stringResource(R.string.no_contacts_yet_message),
                        icon = { IconPerson() },
                    )
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "favorites-header", contentType = "header") {
                FavoritesHeader(Modifier.animateItem().padding(vertical = 6.dp))
            }
            itemsIndexed(
                favorites,
                key = { _, c -> "favorite-${c.id}" },
                // Headers and rows are structurally different subtrees. Left untyped they all
                // share the default null content type, so the list will happily try to reuse a
                // header's slot for a contact row - the reuse then fails and the row composes
                // from scratch anyway, having wasted the attempt.
                contentType = { _, _ -> "contact" },
            ) { idx, contact ->
                GroupedContactRow(idx, favorites.size, itemMotion()) {
                    ContactItem(
                        contact = contact,
                        // Only multi-select tints the row. It used to also tint whichever contact was
                        // open, which meant a plain tap turned the row a different colour at the exact
                        // moment it began morphing into the detail page.
                        isSelected = isSelectionMode && contact.id in selectedIds,
                        showAccountLabels = state.showAccountLabels,
                        accountLabels = state.accountLabels,
                        allGroups = state.groups,
                        decodePhoto = actions::decodePhoto,
                        embeddedInCard = true,
                        sharedKey = contact.id,
                        onClick = {
                            if (isSelectionMode) toggleSelection(contact.id) else actions.openContact(contact)
                        },
                        onLongClick = {
                            if (!isSelectionMode) selectedIds.add(contact.id)
                        }
                    )
                }
            }
        }

        groupedContacts.forEach { (letter, contactsInGroup) ->
            item(key = "letter-header-$letter", contentType = "header") {
                LetterHeader(letter, Modifier.animateItem().padding(vertical = 6.dp))
            }
            itemsIndexed(
                contactsInGroup,
                key = { _, c -> "contact-${c.id}" },
                contentType = { _, _ -> "contact" },
            ) { idx, contact ->
                GroupedContactRow(idx, contactsInGroup.size, itemMotion()) {
                    ContactItem(
                        contact = contact,
                        isSelected = isSelectionMode && contact.id in selectedIds,
                        showAccountLabels = state.showAccountLabels,
                        accountLabels = state.accountLabels,
                        allGroups = state.groups,
                        decodePhoto = actions::decodePhoto,
                        embeddedInCard = true,
                        sharedKey = contact.id,
                        onClick = {
                            if (isSelectionMode) toggleSelection(contact.id) else actions.openContact(contact)
                        },
                        onLongClick = {
                            if (!isSelectionMode) selectedIds.add(contact.id)
                        }
                    )
                }
            }
        }
    }
}
