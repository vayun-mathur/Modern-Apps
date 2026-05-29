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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
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
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.material3.*
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

    val contacts by viewModel.contacts.collectAsState()
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode = selectedIds.isNotEmpty()
    val showAccountLabels by viewModel.showAccountLabels.collectAsState()

    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }
    val groupedContacts = remember(otherContacts) {
        otherContacts.groupBy { it.name.value.firstOrNull()?.uppercaseChar() ?: '#' }
            .mapValues { (_, c) -> c.sortedBy { it.name.value } }
            .toSortedMap()
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

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

    val selectedID = when(backStack.last()) {
        is Route.ContactDetail -> (backStack.last() as Route.ContactDetail).contactId
        is Route.EditContact -> (backStack.last() as Route.EditContact).contactId
        else -> null
    }

    val searchQuery by viewModel.searchQuery.collectAsState()

    // While the search bar has text, intercept back to clear it instead of
    // popping the screen. Empty search → back propagates normally.
    androidx.activity.compose.BackHandler(enabled = searchQuery.isNotEmpty() && !isSelectionMode) {
        viewModel.setSearchQuery("")
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
                            Icon(painterResource(R.drawable.baseline_group_24), contentDescription = stringResource(R.string.add_to_group))
                        }
                        IconButton(onClick = {
                            val toExport = contacts.filter { it.id in selectedIds }
                            scope.launch(Dispatchers.IO) {
                                val vcfFile = context.cacheDir.toOkioPath().resolve("selected_contacts.vcf")
                                FileSystem.SYSTEM.sink(vcfFile).buffer().use { outputStream ->
                                    VcfUtils.exportContacts(toExport, outputStream)
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", vcfFile.toFile())
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/x-vcard"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, resources.getString(R.string.share_contact)))
                            }
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
                            padding = PaddingValues(0.dp)
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val vcfFile = context.cacheDir.toOkioPath().resolve("all_contacts.vcf")
                                FileSystem.SYSTEM.sink(vcfFile).buffer().use { outputStream ->
                                    VcfUtils.exportContacts(contacts, outputStream)
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", vcfFile.toFile())
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/x-vcard"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, resources.getString(R.string.share_contact)))
                            }
                        }) {
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
                        ContactSectionCard {
                            favorites.forEachIndexed { idx, contact ->
                                if (idx > 0) ContactRowDivider()
                                ContactItem(
                                    contact = contact,
                                    isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                                    showAccountLabels = showAccountLabels,
                                    viewModel = viewModel,
                                    embeddedInCard = true,
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (contact.id in selectedIds) {
                                                selectedIds.remove(contact.id)
                                            } else {
                                                selectedIds.add(contact.id)
                                            }
                                        } else {
                                            onContactClick(contact)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            selectedIds.add(contact.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                groupedContacts.forEach { (letter, contactsInGroup) ->
                    item(key = "letter-header-$letter") { LetterHeader(letter) }
                    item(key = "letter-card-$letter") {
                        ContactSectionCard {
                            contactsInGroup.forEachIndexed { idx, contact ->
                                if (idx > 0) ContactRowDivider()
                                ContactItem(
                                    contact = contact,
                                    isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                                    showAccountLabels = showAccountLabels,
                                    viewModel = viewModel,
                                    embeddedInCard = true,
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (contact.id in selectedIds) {
                                                selectedIds.remove(contact.id)
                                            } else {
                                                selectedIds.add(contact.id)
                                            }
                                        } else {
                                            onContactClick(contact)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            selectedIds.add(contact.id)
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListPick(mimeType: String?, contacts: List<Contact>, onClick: (Uri) -> Unit) {
    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }

    val groupedContacts = remember(otherContacts) {
        otherContacts
            .groupBy { it.name.value.first().uppercaseChar() }
            .toSortedMap()
    }

    Scaffold(topBar = { TopAppBar({ Text(stringResource(R.string.app_name)) }) }) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (favorites.isNotEmpty()) {
                item(key = "pick-favorites-header") { FavoritesHeader() }
                item(key = "pick-favorites-card") {
                    ContactSectionCard {
                        favorites.forEachIndexed { idx, contact ->
                            if (idx > 0) ContactRowDivider()
                            ContactItemPick(contact, mimeType, onClick)
                        }
                    }
                }
            }

            groupedContacts.forEach { (letter, contactsInGroup) ->
                item(key = "pick-letter-header-$letter") { LetterHeader(letter) }
                item(key = "pick-letter-card-$letter") {
                    ContactSectionCard {
                        contactsInGroup.forEachIndexed { idx, contact ->
                            if (idx > 0) ContactRowDivider()
                            ContactItemPick(contact, mimeType, onClick)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, onClick: (Uri) -> Unit) {
    if(mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        ContactItem(
            contact = contact,
            isSelected = false,
            showAccountLabels = true,
            onClick = {
                onClick(Uri.withAppendedPath(
                    ContactsContract.RawContacts.CONTENT_URI,
                    contact.id.toString()))
            }
        )
    } else {
        val details = contact.details
        val relevantList = when(mimeType) {
            CDKEmail.CONTENT_ITEM_TYPE -> details.emails
            CDKPhone.CONTENT_ITEM_TYPE -> details.phoneNumbers
            CDKStructuredPostal.CONTENT_ITEM_TYPE -> details.addresses
            else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
        val baseURI = when(mimeType) {
            CDKEmail.CONTENT_ITEM_TYPE -> CDKEmail.CONTENT_URI
            CDKPhone.CONTENT_ITEM_TYPE -> CDKPhone.CONTENT_URI
            CDKStructuredPostal.CONTENT_ITEM_TYPE -> CDKStructuredPostal.CONTENT_URI
            else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
        ContactItem(
            contact = contact,
            isSelected = false,
            showAccountLabels = true,
            onClick = {  },
            dropdownList = relevantList.map { it.value },
            dropdownListClick = { index ->
                onClick(Uri.withAppendedPath(
                    baseURI,
                    relevantList[index].id.toString()
                ))
            }
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
        Icon(painterResource(R.drawable.baseline_star_24), stringResource(R.string.favorites),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.favorites),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ProfilesHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(R.drawable.person_24px), stringResource(R.string.profiles),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.user_profile),
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
 * Rounded surface that wraps an entire section's contacts (favorites, or one
 * letter group) into a single visual card with internal dividers. The
 * individual [ContactItem]s rendered inside should be passed
 * `embeddedInCard = true` so they don't draw their own rounded background.
 */
@Composable
fun ContactSectionCard(
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Column { content() }
    }
}

@Composable
fun ContactRowDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 0.5.dp
    )
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

    val photoBase64 = contact.photo?.photo
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, photoBase64, viewModel) {
        value = if (photoBase64 != null) {
            withContext(Dispatchers.IO) { viewModel?.decodePhoto(photoBase64) }
        } else {
            null
        }
    }

    val allGroups by (viewModel?.groups ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val contactGroups = allGroups.filter { group ->
        contact.details.groups.any { it.groupId == group.id } && group.name.trim().isNotEmpty()
    }

    val trimmedOrg = contact.org.company.trim()
    val showOrg = trimmedOrg.isNotEmpty()
    val showGroups = contactGroups.size > 0

    val content = @Composable {
        val hasDropdown = !dropdownList.isNullOrEmpty()

        key(showOrg, showGroups, contactGroups.size) {
            val itemModifier = if (embeddedInCard) {
                // Inside a ContactSectionCard the parent already clips and
                // paints the background; the row is just a flat ListItem with
                // a translucent selection highlight.
                combinedModifier
            } else {
                combinedModifier
                    .clip(RoundedCornerShape(16.dp, 16.dp, if(hasDropdown) 0.dp else 16.dp, if(hasDropdown) 0.dp else 16.dp))
            }
            val rowContainerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                embeddedInCard -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            ListItem(
                modifier = itemModifier,
                headlineContent = {
                    val nameString = if(contact.nickname.value.isNotBlank()) stringResource(R.string.name_nickname_format, contact.name.value, contact.nickname.value) else contact.name.value
                    Text(
                        text = nameString,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    Box(
                        modifier = Modifier.size(50.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        avatarBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.contact_photo_description, contact.name.value),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        }
                        if (contact.photo == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        color = getAvatarColor(contact.id),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = contact.name.value.firstOrNull()?.uppercase()?: "",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },

                supportingContent = if (showOrg || showGroups) {
                    {
                        Column {
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
                    }
                } else null,

                trailingContent = if (showAccountLabels) {
                    {
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
                } else null,

                colors = ListItemDefaults.colors(
                    containerColor = rowContainerColor
                )
            )
        }
    }

    if (dropdownList != null) {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
            dropdownList.forEachIndexed { idx, it ->
                Spacer(Modifier.height(4.dp))
                ListItem({
                    Text(text = it)
                }, Modifier.clickable {
                    dropdownListClick(idx)
                }.clip(RoundedCornerShape(0.dp, 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp)), colors = ListItemDefaults.colors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ))
            }
        }
    } else {
        content()
    }
}
