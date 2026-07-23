package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.ContactSorting.groupKey
import com.vayunmathur.contacts.util.ContactSorting.sortedLocale
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.CommonSearchBar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import android.content.Intent
import androidx.compose.ui.platform.LocalResources

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactList(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        viewModel.loadContacts()
        viewModel.loadAccounts()
    }

    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode = selectedIds.isNotEmpty()
    val showAccountLabels by viewModel.showAccountLabels.collectAsStateWithLifecycle()

    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }
    val groupedContacts = remember(otherContacts) {
        otherContacts.groupBy { groupKey(it.name.value) }
            .mapValues { (_, c) -> c.sortedLocale() }
            .toSortedMap()
    }

    val toggleSelection = { id: Long ->
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    var isFocusableBySystem by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_selected_contacts_title)) },
            text = { Text(stringResource(R.string.delete_selected_contacts_confirm, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = contacts.filter { it.id in selectedIds }
                    toDelete.forEach { viewModel.deleteContact(it) }
                    selectedIds.clear()
                    showDeleteConfirmation = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val selectedID = when (val last = backStack.last()) {
        is Route.ContactDetail -> last.contactId
        is Route.EditContact -> last.contactId
        else -> null
    }

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    // While the search bar has text, intercept back to clear it instead of
    // popping the screen. Empty search → back propagates normally.
    androidx.activity.compose.BackHandler(enabled = searchQuery.isNotEmpty() && !isSelectionMode) {
        viewModel.setSearchQuery("")
    }

    // When contacts are selected (selection mode), intercept back to unselect
    // instead of closing the app.
    androidx.activity.compose.BackHandler(enabled = isSelectionMode) {
        selectedIds.clear()
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            backStack.add(Route.AddToGroupDialog(selectedIds.toList()))
                        }) {
                            IconGroup()
                        }
                        IconButton(onClick = {
                            shareContactsAsVcf(scope, context, contacts.filter { it.id in selectedIds }, "selected_contacts.vcf", resources.getString(R.string.share_contact))
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
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = stringResource(R.string.search_contacts),
                            padding = PaddingValues(0.dp),
                            modifier = Modifier
                                // Start non-focusable so the field doesn't grab
                                // focus (and raise the keyboard) on screen entry.
                                .focusProperties { canFocus = isFocusableBySystem }
                                // Enable focus on the initial press, before the
                                // text field's own tap handling runs, without
                                // consuming the event so the tap still focuses it.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            if (!isFocusableBySystem) isFocusableBySystem = true
                                        }
                                    }
                                }
                                // Block automatic focus again once focus is lost.
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        isFocusableBySystem = false
                                    }
                                }
                        )
                    },
                    actions = {
                        IconButton(onClick = { shareContactsAsVcf(scope, context, contacts, "all_contacts.vcf", resources.getString(R.string.share_contact)) }) {
                            IconShare()
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if(backStack.last() !is Route.EditContact && !isSelectionMode) {
                FloatingActionButton(onClick = { onAddContactClick() }) {
                    IconAdd()
                }
            }
        },
        bottomBar = {
            if (!isSelectionMode) {
                ContactsBottomNavBar(backStack)
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (favorites.isNotEmpty()) {
                    item(key = "favorites-header") { FavoritesHeader() }
                    item(key = "favorites-card") {
                        GroupedContactSection(count = favorites.size) { idx ->
                            val contact = favorites[idx]
                            ContactItem(
                                contact = contact,
                                isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                                showAccountLabels = showAccountLabels,
                                viewModel = viewModel,
                                embeddedInCard = true,
                                onClick = {
                                    if (isSelectionMode) toggleSelection(contact.id) else onContactClick(contact)
                                },
                                onLongClick = {
                                    if (!isSelectionMode) selectedIds.add(contact.id)
                                }
                            )
                        }
                    }
                }

                groupedContacts.forEach { (letter, contactsInGroup) ->
                    item(key = "letter-header-$letter") { LetterHeader(letter) }
                    item(key = "letter-card-$letter") {
                        GroupedContactSection(count = contactsInGroup.size) { idx ->
                            val contact = contactsInGroup[idx]
                            ContactItem(
                                contact = contact,
                                isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                                showAccountLabels = showAccountLabels,
                                viewModel = viewModel,
                                embeddedInCard = true,
                                onClick = {
                                    if (isSelectionMode) toggleSelection(contact.id) else onContactClick(contact)
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
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListPick(
    mimeType: String?,
    contacts: List<Contact>,
    allowMultiple: Boolean = false,
    selectedUris: List<Uri> = emptyList(),
    onConfirm: () -> Unit = {},
    onClick: (Uri) -> Unit,
) {
    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }

    val groupedContacts = remember(otherContacts) {
        otherContacts
            .groupBy { groupKey(it.name.value) }
            .toSortedMap()
    }

    val selectedSet = selectedUris.toSet()

    Scaffold(
        topBar = { TopAppBar({ Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (allowMultiple) {
                ExtendedFloatingActionButton(onClick = onConfirm) {
                    val label = stringResource(R.string.done) +
                        if (selectedUris.isNotEmpty()) " (${selectedUris.size})" else ""
                    Text(label)
                }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, selectedUris: Set<Uri>, onClick: (Uri) -> Unit) {
    if (mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        val uri = Uri.withAppendedPath(ContactsContract.RawContacts.CONTENT_URI, contact.id.toString())
        ContactItem(
            contact = contact,
            isSelected = uri in selectedUris,
            showAccountLabels = true,
            onClick = { onClick(uri) }
        )
    } else {
        val details = contact.details
        val (relevantList, baseURI) = when(mimeType) {
            CDKEmail.CONTENT_ITEM_TYPE -> details.emails to CDKEmail.CONTENT_URI
            CDKPhone.CONTENT_ITEM_TYPE -> details.phoneNumbers to CDKPhone.CONTENT_URI
            CDKStructuredPostal.CONTENT_ITEM_TYPE -> details.addresses to CDKStructuredPostal.CONTENT_URI
            else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
        val itemUris = relevantList.map { Uri.withAppendedPath(baseURI, it.id.toString()) }
        ContactItem(
            contact = contact,
            isSelected = itemUris.any { it in selectedUris },
            showAccountLabels = true,
            onClick = {  },
            dropdownList = relevantList.map { it.value },
            dropdownListClick = { index -> onClick(itemUris[index]) }
        )
    }
}

@Composable
fun FavoritesHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconStar(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.favorites),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LetterHeader(letter: Char, modifier: Modifier = Modifier) {
    Text(
        text = letter.toString(),
        modifier = modifier.padding(vertical = 8.dp, horizontal = 4.dp), // Add slight horizontal padding
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

fun getAvatarColor(id: Long): Color {
    val colors = listOf(
        Color(0xFF6C3800),
        Color(0xFF00502A),
        Color(0xFF8B0053),
        Color(0xFF891916),
        Color(0xFF004B5B),
        Color(0xFF5528A1),
    )
    val index = (id % colors.size).toInt()
    return colors[index]
}

/**
 * Grouped-cards pattern: each row is its own rounded [Surface], but the
 * corners that meet a sibling in the same group are flattened so the group
 * reads as one section while remaining visually distinct cards. Mirrors what
 * [DetailItem]+[groupShape] does on the contact details page.
 *
 * [row] should render a flat row (e.g. [ContactItem] with `embeddedInCard =
 * true`) — the outer Surface handles the background and clipping.
 */
@Composable
fun GroupedContactSection(
    count: Int,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    topAttached: Boolean = false,
    row: @Composable (index: Int) -> Unit,
) {
    if (count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until count) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape(i, count, flatTop = topAttached && i == 0),
                color = containerColor,
            ) { row(i) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactItem(
    contact: Contact,
    isSelected: Boolean,
    showAccountLabels: Boolean,
    viewModel: ContactViewModel? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    dropdownList: List<String>? = null,
    dropdownListClick: (Int) -> Unit = {},
    embeddedInCard: Boolean = false,
    modifier: Modifier = Modifier
) {
    val combinedModifier = if (dropdownList == null) {
        modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        modifier
    }

    val allGroups by (viewModel?.groups ?: flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())
    val contactGroups = contactGroupsOf(contact, allGroups)

    val trimmedOrg = contact.org.company.trim()
    val showOrg = trimmedOrg.isNotEmpty()
    val showGroups = contactGroups.isNotEmpty()

    val content = @Composable {
        val hasDropdown = !dropdownList.isNullOrEmpty()

        key(showOrg, showGroups, contactGroups.size) {
            val itemModifier = if (embeddedInCard) {
                combinedModifier
            } else {
                val r = if (hasDropdown) 0.dp else 16.dp
                combinedModifier.clip(RoundedCornerShape(16.dp, 16.dp, r, r))
            }
            val rowContainerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                embeddedInCard -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            // A plain Row/Column layout is used instead of Material3 ListItem
            // because ListItem queries its children's baseline alignment lines,
            // which throws a framework NPE when remeasured inside the Navigation3
            // adaptive lookahead pass on configuration change (rotation).
            Row(
                modifier = itemModifier
                    .fillMaxWidth()
                    .background(rowContainerColor)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContactAvatar(contact, viewModel, Modifier.size(50.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactDisplayName(contact),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showOrg) {
                        Text(trimmedOrg)
                    }
                    if (showGroups) {
                        Text(
                            text = contactGroups.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (showAccountLabels) {
                    Spacer(Modifier.width(16.dp))
                    val onDevice = stringResource(R.string.on_device)
                    Text(
                        text = contact.accountName ?: onDevice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }
            }
        }
    }

    if (dropdownList != null) {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
            dropdownList.forEachIndexed { idx, it ->
                Spacer(Modifier.height(4.dp))
                SafeListItem(
                    content = {
                        Text(text = it)
                    },
                    modifier = Modifier.clickable {
                        dropdownListClick(idx)
                    }.clip(RoundedCornerShape(0.dp, 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp)),
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                )
            }
        }
    } else {
        content()
    }
}
