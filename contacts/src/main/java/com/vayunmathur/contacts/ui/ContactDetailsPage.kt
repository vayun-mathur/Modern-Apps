package com.vayunmathur.contacts.ui

import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.ui.dialogs.MoveDialogHost
import com.vayunmathur.contacts.util.ContactDetailsUiState
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.contactFlow
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconMove
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.ringtonePickerIntent
import com.vayunmathur.library.ui.ringtonePickerResult
import com.vayunmathur.library.ui.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Binds [ContactViewModel] to the stateless [ContactDetailsScreen]. */
@Composable
fun ContactDetailsPage(
    viewModel: ContactViewModel,
    contactId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: () -> Unit,
    showBackButton: Boolean = true
) {
    val context = LocalContext.current
    val contactsList by viewModel.contacts.collectAsStateWithLifecycle()
    val contactFromFlow by remember { viewModel.contactFlow(contactId) }.collectAsStateWithLifecycle(initialValue = null)
    val contactFromProvider by produceState<Contact?>(initialValue = null, contactId, contactsList.size) {
        value = withContext(Dispatchers.IO) {
            viewModel.getContact(contactId) ?: com.vayunmathur.contacts.data.Contact.getContact(context, contactId)
        }
    }
    val contact = contactFromFlow ?: contactFromProvider

    if (contact == null) {
        if (contactsList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(stringResource(R.string.contact_not_found))
            }
        }
        return
    }

    val platforms by produceState(ContactPlatforms(), contactId, contact.details) {
        value = withContext(Dispatchers.IO) { PackageUtils.getContactPlatforms(context, contactId) }
    }
    val isGoogleMeetInstalled by produceState(false) {
        value = withContext(Dispatchers.IO) { PackageUtils.isGoogleMeetInstalled(context) }
    }
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    val shareContactLabel = stringResource(R.string.share_contact)
    val ringtonePickerTitle = stringResource(R.string.select_ringtone)
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.saveContact(contact.copy(customRingtone = ringtonePickerResult(result.data)))
        }
    }

    var moveContactIds by remember(contactId) { mutableStateOf<List<Long>?>(null) }

    moveContactIds?.let { ids ->
        MoveDialogHost(
            viewModel = viewModel,
            contactIds = ids,
            onDismiss = { moveContactIds = null },
            // The moved contact no longer exists: pop before the re-sync
            // flips this page to "contact not found".
            onTransferred = onBack,
        )
    }

    ContactDetailsScreen(
        state = ContactDetailsUiState(
            contact = contact,
            groups = groups,
            platforms = platforms,
            isGoogleMeetInstalled = isGoogleMeetInstalled,
        ),
        actions = object : ContactsActions by viewModel {
            override fun closeContact() = onBack()

            override fun editContact(contactId: Long) = onEdit(contactId)

            override fun confirmDeleteContact(contact: Contact) = onDelete()

            override fun moveContacts(contactIds: List<Long>) {
                moveContactIds = contactIds
            }

            override fun shareContacts(contacts: List<Contact>, filename: String) {
                shareContactsAsVcf(
                    scope, context, contacts, filename, shareContactLabel,
                    groups.associate { it.id to it.name }
                )
            }

            override fun pickRingtone(contact: Contact) {
                ringtoneLauncher.launch(
                    ringtonePickerIntent(
                        contact.customRingtone,
                        RingtoneManager.TYPE_RINGTONE,
                        ringtonePickerTitle,
                    )
                )
            }
        },
        showBackButton = showBackButton,
    )
}

private const val STAGGER_WINDOW_MILLIS = 400L

/**
 * The contact details page, with no dependency on the ViewModel so it can be rendered from
 * a `@Preview`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsScreen(
    state: ContactDetailsUiState,
    actions: ContactsActions,
    showBackButton: Boolean = true,
) {
    val contact = state.contact
    val details = contact.details
    val platforms = state.platforms

    // Only what is on screen when the page arrives should stagger in. A section further down is
    // composed because the user scrolled to it, and delaying that would just feel like lag.
    var arriving by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(STAGGER_WINDOW_MILLIS)
        arriving = false
    }

    DetailLazyColumn(
        title = "",
        onNavigateBack = if (showBackButton) actions::closeContact else null,
        actions = {
            IconButton({
                actions.saveContact(contact.copy(isFavorite = !contact.isFavorite))
            }) {
                if (!contact.isFavorite) IconStarBorder(tint = MaterialTheme.colorScheme.onSurface)
                else IconStar(tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { actions.editContact(contact.id) }) {
                IconEdit()
            }
            IconButton(onClick = {
                actions.shareContacts(listOf(contact), "${contact.name.value.replace(' ', '_')}.vcf")
            }) {
                IconShare()
            }
            IconButton(onClick = { actions.moveContacts(listOf(contact.id)) }) {
                IconMove()
            }
            IconButton(onClick = { actions.confirmDeleteContact(contact) }) {
                IconDelete()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {

            item {
                // Not staggered: this is where the container transform from the list row lands, and a
                // second entrance animation on top of the morph is what made it look broken before.
                ProfileHeader(contact, actions::decodePhoto)
            }

            item {
                Box(Modifier.staggeredEntrance(index = 0, arriving = arriving)) {
                    ActionButtonsRow(
                        details.phoneNumbers,
                        details.emails.firstOrNull()?.address,
                        platforms,
                        state.isGoogleMeetInstalled,
                        details.commPref,
                        onSaveCommPref = { pref ->
                            val existingId = contact.details.commPrefs.firstOrNull()?.id ?: 0L
                            actions.saveContact(
                                contact.copy(
                                    details = contact.details.copy(
                                        commPrefs = listOf(pref.copy(id = existingId))
                                    )
                                )
                            )
                        }
                    )
                }
            }

            if (details.phoneNumbers.isNotEmpty()) {
                item {
                    PhonesSection(details, platforms, arriving)
                }
            }
            if (details.emails.isNotEmpty()) {
                item {
                    EmailsSection(details, arriving)
                }
            }
            if (details.addresses.isNotEmpty()) {
                item {
                    AddressesSection(details)
                }
            }

            if (details.dates.isNotEmpty()) {
                item {
                    DatesSection(contact, details)
                }
            }
            if (details.genders.any { it.code.isNotBlank() }) {
                item {
                    GenderSection(contact, details)
                }
            }

            if (contact.note.content.isNotEmpty()) {
                item {
                    NoteSection(contact)
                }
            }

            if (details.groups.isNotEmpty()) {
                item {
                    GroupsSection(contact, state.groups)
                }
            }

            // A SIM's address book has nowhere to keep a ringtone.
            if (!isSimAccountType(contact.accountType)) {
                item {
                    RingtoneSection(contact, actions)
                }
            }
        }
}
