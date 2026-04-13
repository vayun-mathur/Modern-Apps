package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.sink
import kotlin.io.encoding.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactList(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit
) {
    val contacts by viewModel.contacts.collectAsState()

    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }
    val groupedContacts = remember(otherContacts) {
        otherContacts.groupBy { it.name.value.firstOrNull()?.uppercaseChar() ?: '#' }
            .mapValues { (_, c) -> c.sortedBy { it.name.value } }
            .toSortedMap()
    }


    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/vcard"),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch {
                    try {
                        context.contentResolver.openOutputStream(it)?.sink()?.use { outputStream ->
                            VcfUtils.exportContacts(contacts, outputStream)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    )

    val selectedID = when(backStack.last()) {
        is Route.ContactDetail -> (backStack.last() as Route.ContactDetail).contactId
        is Route.EditContact -> (backStack.last() as Route.EditContact).contactId
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar({Text(stringResource(R.string.app_name))}, actions = {
                IconButton(onClick = { backStack.add(Route.Settings) }) {
                    IconSettings()
                }
                IconButton(onClick = {
                    exportLauncher.launch("contacts.vcf")
                }) {
                    Icon(painterResource(R.drawable.download_24px),
                        contentDescription = stringResource(R.string.export_contacts)
                    )
                }
            })
        },
        floatingActionButton = {
            if(backStack.last() !is Route.EditContact) {
                FloatingActionButton(onClick = { onAddContactClick() }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
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
                        isSelected = selectedID == contact.id,
                        onClick = { onContactClick(contact) },
                    )
                }
            }

            groupedContacts.forEach { (letter, contactsInGroup) ->
                item { LetterHeader(letter) }
                items(contactsInGroup, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        isSelected = selectedID == contact.id,
                        onClick = { onContactClick(contact) },
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, onClick: (Uri) -> Unit) {
    if(mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        ContactItem(contact, false, {
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
        ContactItem(contact, false, {  }, dropdownList = relevantList.map { it.value }, dropdownListClick = { index ->
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
    onClick: () -> Unit,
    dropdownList: List<String>? = null,
    dropdownListClick: (Int) -> Unit = {}
) {
    val modifier = if (dropdownList == null) {
        Modifier.combinedClickable(
            onClick = onClick,
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
                var nameString = contact.name.value
                if(contact.nickname.value.isNotBlank()) nameString += " (${contact.nickname.value})"
                Text(
                    text = nameString,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
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
                            contentDescription = "${contact.name} photo",
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

            trailingContent = {
                val onDevice = stringResource(R.string.on_device)
                Text(contact.accountName ?: onDevice)
            },

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
