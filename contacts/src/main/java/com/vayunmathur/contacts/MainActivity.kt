package com.vayunmathur.contacts

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.ui.*
import com.vayunmathur.contacts.ui.dialogs.*
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import androidx.compose.ui.res.painterResource

class MainActivity : ComponentActivity() {
    private val importUris = mutableStateOf<List<Uri>>(emptyList())
    private val externalRoute = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
            var hasPermissions by remember { mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) }
            DynamicTheme {
                if (!hasPermissions) {
                    NoPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    val viewModel: ContactViewModel = viewModel()
                    
                    val uris by importUris
                    if (uris.isNotEmpty()) {
                        ImportVcfDialog(viewModel, uris) { importUris.value = emptyList() }
                    }

                    // If the app was launched with ACTION_PICK/GET_CONTENT, forward to the picker flow.
                    if (intent.action == Intent.ACTION_PICK || intent.action == Intent.ACTION_GET_CONTENT) {
                        var type = intent.type
                        if (intent.data?.toString()?.contains("phones") == true) {
                            type = CDKPhone.CONTENT_ITEM_TYPE
                        }
                        val contacts by viewModel.contacts.collectAsStateWithLifecycle()
                        val allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                        if (allowMultiple) {
                            val selected = remember { mutableStateListOf<Uri>() }
                            ContactListPick(
                                mimeType = type,
                                contacts = contacts,
                                allowMultiple = true,
                                selectedUris = selected,
                                onConfirm = { finishPickWithSelection(selected) },
                                onClick = { uri -> if (!selected.remove(uri)) selected.add(uri) },
                            )
                        } else {
                            ContactListPick(type, contacts) {
                                val resultIntent = Intent().apply {
                                    data = it
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                setResult(RESULT_OK, resultIntent)
                                finish()
                            }
                        }
                    } else {
                        val route by externalRoute
                        Box(Modifier.fillMaxSize().onFileDrop { importUris.value = it }) {
                            Navigation(viewModel, route, onExit = { finish() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Return a multi-select pick result: items go in ClipData (per EXTRA_ALLOW_MULTIPLE). */
    private fun finishPickWithSelection(uris: List<Uri>) {
        val resultIntent = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty()) {
                // First item also as data for callers that read a single result.
                data = uris.first()
                val clip = ClipData.newUri(contentResolver, "contacts", uris.first())
                for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
                clipData = clip
            }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        val type = intent.type ?: ""
        val isVcf = type.contains("vcard") || type.contains("vcf") || intent.data?.path?.endsWith(".vcf", ignoreCase = true) == true

        if (isVcf) {
            val uris = IntentHelper.getUrisFromIntent(intent)
            if (uris.isNotEmpty()) {
                importUris.value = uris
            }
        }

        val action = intent.action
        if (action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT || action == Intent.ACTION_INSERT
            || action == ContactsContract.QuickContact.ACTION_QUICK_CONTACT
            || action == "com.android.contacts.action.QUICK_CONTACT") {
            externalRoute.value = when (action) {
                Intent.ACTION_INSERT -> {
                    Route.EditContact(
                        contactId = null,
                        name = intent.getStringExtra(ContactsContract.Intents.Insert.NAME),
                        phone = intent.getStringExtra(ContactsContract.Intents.Insert.PHONE),
                        email = intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL),
                        company = intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY),
                        jobTitle = intent.getStringExtra(ContactsContract.Intents.Insert.JOB_TITLE),
                        notes = intent.getStringExtra(ContactsContract.Intents.Insert.NOTES)
                    )
                }
                Intent.ACTION_EDIT -> {
                    val contactId = resolveContactId(intent.data)
                    Route.EditContact(
                        contactId = contactId,
                        name = intent.getStringExtra(ContactsContract.Intents.Insert.NAME),
                        phone = intent.getStringExtra(ContactsContract.Intents.Insert.PHONE),
                        email = intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL)
                    )
                }
                else -> {
                    val path = intent.data?.path ?: ""
                    val mimeType = intent.type
                    when {
                        path.contains("/groups") || mimeType?.contains("group") == true -> {
                            val groupId = intent.data?.lastPathSegment?.toLongOrNull()
                            Route.GroupsList(groupId)
                        }
                        else -> {
                            resolveContactId(intent.data)?.let { id -> Route.ContactDetail(id) }
                                ?: extractPhoneNumber(intent)?.takeIf { it.isNotBlank() }?.let { number ->
                                    Route.EditContact(contactId = null, phone = number)
                                }
                        }
                    }
                }
            }
        }
    }

    private fun extractPhoneNumber(intent: Intent): String? {
        intent.getStringExtra(ContactsContract.Intents.Insert.PHONE)?.let { return it }
        val uri = intent.data ?: return null
        if (uri.scheme == "tel") {
            return uri.schemeSpecificPart
        }
        if (uri.authority == ContactsContract.AUTHORITY && uri.path?.contains("phone_lookup") == true) {
            return uri.lastPathSegment
        }
        return null
    }

    private fun resolveContactId(uri: Uri?): Long? {
        if (uri == null) return null
        val path = uri.path ?: return null

        // For raw_contacts URIs, the last segment is already a raw contact ID
        if (path.startsWith("/raw_contacts/") || path.contains("/raw_contacts")) {
            return uri.lastPathSegment?.toLongOrNull()
        }

        // For contacts/lookup or contacts/<id> URIs, extract the aggregated contact ID
        val aggregatedId: Long? = when {
            path.contains("/lookup/") -> {
                ContactsContract.Contacts.lookupContact(contentResolver, uri)?.let {
                    ContentUris.parseId(it)
                }
            }
            path.startsWith("/contacts/") || path == "/contacts" -> {
                uri.lastPathSegment?.toLongOrNull()
            }
            else -> uri.lastPathSegment?.toLongOrNull()
        }

        if (aggregatedId == null) return null

        // Convert aggregated contact ID to raw contact ID.
        // Pick the most visible raw contact: prefer STARRED, then lowest _ID.
        contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(aggregatedId.toString()),
            "${ContactsContract.RawContacts.STARRED} DESC, ${ContactsContract.RawContacts._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }
}

@Composable
fun NoPermissionsScreen(permissions: Array<String>, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            com.vayunmathur.library.ui.Button(
                {
                    permissionRequestor.launch(permissions)
                }, Modifier.align(Alignment.Center)
            ) {
                Text(text = stringResource(R.string.grant_contacts_permission))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Navigation(viewModel: ContactViewModel, initialRoute: Route? = null, onExit: () -> Unit = {}) {
    val backStack = rememberNavBackStack<Route>(initialRoute ?: Route.ContactsList)

    LaunchedEffect(initialRoute) {
        if (initialRoute != null && backStack.last() != initialRoute) {
            backStack.add(initialRoute)
        }
    }

    fun goBack() {
        if (backStack.backStack.size > 1) {
            backStack.pop()
        } else {
            onExit()
        }
    }

    val isCalendarSyncEnabled by viewModel.isCalendarSyncEnabled.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(isCalendarSyncEnabled) {
        if (isCalendarSyncEnabled) {
            com.vayunmathur.contacts.util.CalendarWorker.schedule(context)
        }
    }

    MainNavigation(backStack) {
        entry<Route.ContactsList>(metadata = ListPage {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(stringResource(R.string.select_contact_hint))
            }
        }) {
            ContactList(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = { contact ->
                    if (backStack.last() is Route.ContactDetail || backStack.last() is Route.EditContact) {
                        backStack.setLast(Route.ContactDetail(contact.id))
                    } else {
                        backStack.add(Route.ContactDetail(contact.id))
                    }
                },
                onAddContactClick = {
                    if (backStack.last() is Route.ContactDetail) {
                        backStack.pop()
                    }
                    backStack.add(Route.EditContact(null))
                }
            )
        }

        entry<Route.GroupsList>(metadata = ListPage {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(stringResource(R.string.select_group_hint))
            }
        }) { key ->
            GroupsPage(viewModel, backStack, key.expandGroupId)
        }

        entry<Route.ContactDetail>(metadata = ListDetailPage()) { key ->
            ContactDetailsPage(
                viewModel = viewModel,
                contactId = key.contactId,
                onBack = { goBack() },
                onEdit = { id -> backStack.add(Route.EditContact(id)) },
                onDelete = {
                    // Show the delete confirmation dialog using the contact id and name
                    val contact = viewModel.getContact(key.contactId)
                    backStack.add(Route.EventDeleteConfirmDialog(key.contactId, contact?.name?.value))
                },
                showBackButton = true
            )
        }
        entry<Route.EditContact>(metadata = ListDetailPage()) { key ->
            EditContactPage(backStack, viewModel, key, onExit = { goBack() })
        }

        entry<Route.Settings>(metadata = ListDetailPage()) {
            SettingsPage(viewModel, backStack)
        }

        entry<Route.AddAccountDialog>(metadata = DialogPage()) {
            AddAccountDialog(viewModel) { backStack.pop() }
        }

        entry<Route.EventDatePickerDialog>(metadata = DialogPage()) { key ->
            EventDatePickerDialog(key.id, key.initialDate) { backStack.pop() }
        }

        entry<Route.EventDeleteConfirmDialog>(metadata = DialogPage()) { key ->
            EventDeleteConfirmDialog(key.contactId, key.contactName, viewModel, onConfirm = {
                // After confirming deletion, pop the dialog and the detail page
                backStack.pop()
                backStack.pop()
            }, onDismiss = {
                // Only close the dialog
                backStack.pop()
            })
        }

        entry<Route.AddToGroupDialog>(metadata = DialogPage()) { key ->
            AddToGroupDialog(viewModel, key.contactIds) { backStack.pop() }
        }

        entry<Route.CropPhoto>(metadata = ListDetailPage()) { key ->
            val decodedUri = java.net.URLDecoder.decode(key.uri, "UTF-8")
            CropPhotoScreen(
                uri = decodedUri,
                onCropComplete = { bitmap ->
                    viewModel.setEditDraftPhotoFromBitmap(bitmap)
                    backStack.pop()
                },
                onCancel = { backStack.pop() }
            )
        }
    }
}

sealed interface Route: NavKey {
    @Serializable
    object ContactsList : Route

    @Serializable
    data class GroupsList(val expandGroupId: Long? = null) : Route

    @Serializable
    data class AddToGroupDialog(val contactIds: List<Long>) : Route

    @Serializable
    data class ContactDetail(val contactId: Long) : Route

    @Serializable
    data class EditContact(
        val contactId: Long?,
        val name: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val company: String? = null,
        val jobTitle: String? = null,
        val notes: String? = null
    ) : Route

    @Serializable
    data class EventDatePickerDialog(val id: String, val initialDate: LocalDate?): Route

    @Serializable
    data class EventDeleteConfirmDialog(val contactId: Long, val contactName: String?): Route

    @Serializable
    object Settings : Route

    @Serializable
    object AddAccountDialog : Route

    @Serializable
    data class CropPhoto(val uri: String) : Route
}
