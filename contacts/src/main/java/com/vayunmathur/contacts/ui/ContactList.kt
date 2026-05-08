package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Build

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
import kotlin.io.encoding.Base64

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconClose
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import android.content.Intent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactList(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit
) {
    val context = LocalContext.current
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

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            if (!isSelectionMode) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Contacts") },
                        label = { Text("Contacts") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Star, contentDescription = "Highlights") },
                        label = { Text("Highlights") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Build, contentDescription = "Fix & manage") },
                        label = { Text("Fix & manage") }
                    )
                }
            }
        },
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
                                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_contact)))
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
                    title = { Text(stringResource(R.string.app_name)) },
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
                                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_contact)))
                            }
                        }) {
                            IconShare()
                        }
                        IconButton(onClick = { backStack.add(Route.Settings) }) {
                            IconSettings()
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
            if(backStack.last() !is Route.EditContact && !isSelectionMode) {
                FloatingActionButton(onClick = { onAddContactClick() }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
        if (selectedTab == 0) {
            LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (favorites.isNotEmpty()) {
                item { FavoritesHeader() }
                items(favorites, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                        showAccountLabels = showAccountLabels,
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

            groupedContacts.forEach { (letter, contactsInGroup) ->
                item { LetterHeader(letter) }
                items(contactsInGroup, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                        showAccountLabels = showAccountLabels,
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListPick(mimeType: String?, contacts: List<Contact>, onClick: (Uri) -> Unit) {
    val (favorites, otherContacts) = contacts.partition { it.isFavorite }

    val groupedContacts = otherContacts
        .groupBy { it.name.value.first().uppercaseChar() }
        .toSortedMap()

    Scaffold(topBar = { TopAppBar({ Text(stringResource(R.string.app_name)) }) }) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (favorites.isNotEmpty()) {
                item { FavoritesHeader() }
                items(favorites, key = { it.id }) { contact ->
                    ContactItemPick(contact, mimeType, onClick)
                }
            }

            groupedContacts.forEach { (letter, contactsInGroup) ->
                item { LetterHeader(letter) }
                items(contactsInGroup, key = { it.id }) { contact ->
                    ContactItemPick(contact, mimeType, onClick)
                }
            }
        }
        } else if (selectedTab == 1) {
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Highlights & Favorites", style = MaterialTheme.typography.headlineMedium)
                Text("Your favorite contacts will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (selectedTab == 2) {
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
                Text("Fix & manage", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 24.dp))
                
                ListItem(
                    headlineContent = { Text("Merge & fix") },
                    supportingContent = { Text("Fix duplicate contacts") },
                    leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                    modifier = Modifier.clickable { /* Handle merge */ }
                )
                ListItem(
                    headlineContent = { Text("Import from file") },
                    supportingContent = { Text("Import contacts from .vcf files") },
                    leadingContent = { Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null) },
                    modifier = Modifier.clickable { /* Start import */ }
                )
                ListItem(
                    headlineContent = { Text("Export to file") },
                    supportingContent = { Text("Export contacts to .vcf files") },
                    leadingContent = { Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null) },
                    modifier = Modifier.clickable { /* Start export */ }
                )
                ListItem(
                    headlineContent = { Text("Blocked numbers") },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.clickable { /* Show blocked */ }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, onClick: (Uri) -> Unit) {
    if(mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        ContactItem(contact, false, true, {
            onClick(Uri.withAppendedPath(
                ContactsContract.RawContacts.CONTENT_URI,
                contact.id.toString()))
        })
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
        ContactItem(contact, false, true, {  }, dropdownList = relevantList.map { it.value }, dropdownListClick = { index ->
            onClick(Uri.withAppendedPath(
                baseURI,
                relevantList[index].id.toString()
            ))
        })
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


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactItem(
    contact: Contact,
    isSelected: Boolean,
    showAccountLabels: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    dropdownList: List<String>? = null,
    dropdownListClick: (Int) -> Unit = {}
) {
    val modifier = if (dropdownList == null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier
    }

    val photoBase64 = contact.photo?.photo
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, photoBase64) {
        if (photoBase64 != null) {
            value = withContext(Dispatchers.IO) {
                val bytes = Base64.decode(photoBase64)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    Column {
        val hasDropdown = !dropdownList.isNullOrEmpty()
        ListItem(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp, 16.dp, if(hasDropdown) 0.dp else 16.dp, if(hasDropdown) 0.dp else 16.dp)),
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

            supportingContent = {
                if(contact.org.company.isNotEmpty()) {
                    Text(contact.org.company)
                }
            },

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
                containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        )
        dropdownList?.forEachIndexed { idx, it ->
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
}
