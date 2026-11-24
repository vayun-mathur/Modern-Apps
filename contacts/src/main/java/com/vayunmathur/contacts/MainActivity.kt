package com.vayunmathur.contacts
import com.vayunmathur.contacts.intents.uriPhoneNumber
import com.vayunmathur.contacts.intents.isVcfIntent
import com.vayunmathur.contacts.intents.buildInsertPrefill

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.TabbedPagerScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.ui.dialogs.AddAccountDialog
import com.vayunmathur.contacts.ui.dialogs.AddToGroupDialog
import com.vayunmathur.contacts.ui.ContactDetailsPage
import com.vayunmathur.contacts.ui.ContactList
import com.vayunmathur.contacts.ui.ContactListPick
import com.vayunmathur.contacts.ui.CropPhotoScreen
import com.vayunmathur.contacts.ui.EditContactPage
import com.vayunmathur.contacts.ui.dialogs.EventDeleteConfirmDialog
import com.vayunmathur.contacts.ui.GroupsPage
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.contacts.ui.ImportVcfScreen
import com.vayunmathur.contacts.ui.InsertOrEditContactScreen
import com.vayunmathur.contacts.ui.SettingsPage
import com.vayunmathur.contacts.ui.dialogs.EventDatePickerDialog
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.loadAccounts
import com.vayunmathur.contacts.platform.setEditDraftPhotoFromBitmap
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Contacts entry activity: gates on contacts permissions, then hosts the pager
 * navigation and pick/import intent flows.
 */
class MainActivity : MaActivity() {
    private val importUris = mutableStateOf<List<String>>(emptyList())
    private val externalRoute = mutableStateOf<Route?>(null)

    /** Legacy quick-contact action string and groups deep-link path segment. */
    companion object {
        private const val QUICK_CONTACT_LEGACY_ACTION = "com.android.contacts.action.QUICK_CONTACT"
        private const val GROUPS_PATH_SEGMENT = "/groups"
    }

    override fun onCreateBeforeContent() {
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        handleIntent(intent)
    }

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.grant_contacts_permission),
                requirements = listOf(
                    PermissionRequirement.Runtime(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.WRITE_CONTACTS,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE
                        )
                    )
                )
            )
        ) {
            val viewModel: ContactViewModel = viewModel()
            // Wear companion: publish the (capped, chunked) snapshot whenever the
            // book changes; a no-watch phone simply records NO_WATCH.
            val wearSync = remember {
                com.vayunmathur.contacts.sync.WearContactsSync(
                    applicationContext,
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                    ),
                )
            }
            LaunchedEffect(wearSync) { wearSync.start() }
            val allContacts by viewModel.allContactsForExport.collectAsStateWithLifecycle()
            LaunchedEffect(wearSync, allContacts) {
                if (allContacts.isNotEmpty()) wearSync.publish(allContacts)
            }
            MainContent(viewModel, intent, onFinishPick = { finishPick(it) })
        }
    }

    @Composable
    private fun MainContent(
        viewModel: ContactViewModel,
        launchIntent: Intent,
        onFinishPick: (List<Uri>) -> Unit,
    ) {
        val uris by importUris
        val importRoute = if (uris.isNotEmpty()) Route.ImportVcf(uris) else null

        // If the app was launched with ACTION_PICK/GET_CONTENT, forward to the picker flow.
        val isPickAction =
            launchIntent.action == Intent.ACTION_PICK ||
                launchIntent.action == Intent.ACTION_GET_CONTENT
        if (isPickAction) {
            PickFlow(viewModel, launchIntent, onFinishPick)
        } else {
            val route by externalRoute
            val initialRoute = importRoute ?: route
            val dropModifier = Modifier
                .fillMaxSize()
                .onFileDrop { dropped -> importUris.value = dropped.map { it.toString() } }
            Box(dropModifier) {
                Navigation(
                    viewModel,
                    initialRoute,
                    onExit = { finish() },
                ) { importUris.value = emptyList() }
            }
        }
    }

    @Composable
    private fun PickFlow(
        viewModel: ContactViewModel,
        launchIntent: Intent,
        onFinishPick: (List<Uri>) -> Unit,
    ) {
        var type = launchIntent.type
        if (launchIntent.data?.toString()?.contains("phones") == true) {
            type = CDKPhone.CONTENT_ITEM_TYPE
        }
        val contacts by viewModel.contacts.collectAsStateWithLifecycle()
        val allowMultiple = launchIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        if (allowMultiple) {
            val selected = remember { mutableStateListOf<Uri>() }
            ContactListPick(
                mimeType = type,
                contacts = contacts,
                allowMultiple = true,
                selectedUris = selected,
                onConfirm = { onFinishPick(selected) },
                onClick = { uri -> if (!selected.remove(uri)) selected.add(uri) },
            )
        } else {
            ContactListPick(type, contacts) { uri ->
                val resultIntent = Intent().apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    private fun finishPick(uris: List<Uri>) = finishPickWithSelection(uris)

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
        if (isVcfIntent(intent)) {
            val uris = IntentHelper.getUrisFromIntent(intent)
            if (uris.isNotEmpty()) {
                importUris.value = uris.map { it.toString() }
            }
        }

        val action = intent.action
        if (!isContactViewAction(action)) return
        externalRoute.value = routeForAction(action, intent)
    }

    private fun isContactViewAction(action: String?): Boolean =
        action == Intent.ACTION_VIEW ||
            action == Intent.ACTION_EDIT ||
            action == Intent.ACTION_INSERT ||
            action == Intent.ACTION_INSERT_OR_EDIT ||
            action == ContactsContract.Intents.SHOW_OR_CREATE_CONTACT ||
            action == ContactsContract.QuickContact.ACTION_QUICK_CONTACT ||
            action == QUICK_CONTACT_LEGACY_ACTION

    private fun routeForAction(action: String?, intent: Intent): Route? =
        when (action) {
            Intent.ACTION_INSERT ->
                Route.EditContact(contactId = null, prefill = buildInsertPrefill(intent))
            Intent.ACTION_INSERT_OR_EDIT, ContactsContract.Intents.SHOW_OR_CREATE_CONTACT ->
                Route.InsertOrEditContact(prefill = prefillWithUriPhone(intent))
            Intent.ACTION_EDIT ->
                Route.EditContact(
                    contactId = resolveContactId(intent.data),
                    prefill = buildInsertPrefill(intent),
                )
            else -> routeForViewAction(intent)
        }

    private fun prefillWithUriPhone(intent: Intent): ContactPrefill {
        var prefill = buildInsertPrefill(intent)
        if (prefill.phones.isEmpty()) {
            uriPhoneNumber(intent)?.takeIf { it.isNotBlank() }?.let { number ->
                prefill = prefill.copy(phones = listOf(PrefillValue(number)))
            }
        }
        return prefill
    }

    private fun routeForViewAction(intent: Intent): Route? {
        val path = intent.data?.path ?: ""
        val mimeType = intent.type
        val isGroupsTarget =
            path.contains(GROUPS_PATH_SEGMENT) || mimeType?.contains("group") == true
        if (isGroupsTarget) {
            val groupId = intent.data?.lastPathSegment?.toLongOrNull()
            return Route.GroupsList(groupId)
        }
        return resolveContactId(intent.data)?.let { id -> Route.ContactDetail(id) }
            ?: routeForPhoneNumber(intent)
    }

    private fun routeForPhoneNumber(intent: Intent): Route.EditContact? =
        uriPhoneNumber(intent)?.takeIf { it.isNotBlank() }?.let { number ->
            Route.EditContact(
                contactId = null,
                prefill = ContactPrefill(phones = listOf(PrefillValue(number))),
            )
        }

    private fun resolveContactId(uri: Uri?): Long? {
        if (uri == null) return null
        val path = uri.path ?: return null
        return when {
            // For raw_contacts URIs, the last segment is already a raw contact ID
            isRawContactsPath(path) -> uri.lastPathSegment?.toLongOrNull()
            // A phone_lookup URI identifies a contact by number, not by ID: resolve
            // the number through PhoneLookup before the generic ID extraction below.
            path.contains("/phone_lookup/") -> resolvePhoneLookupContactId(uri)
            else -> aggregatedContactId(uri, path)?.let { rawContactIdForAggregated(it) }
        }
    }

    private fun isRawContactsPath(path: String): Boolean =
        path.startsWith("/raw_contacts/") || path.contains("/raw_contacts")

    private fun resolvePhoneLookupContactId(uri: Uri): Long? {
        val number = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        val phoneLookupId = phoneLookupIdForNumber(number) ?: return null
        return rawContactIdForAggregated(phoneLookupId)
    }

    private fun phoneLookupIdForNumber(number: String): Long? {
        val filterUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        contentResolver.query(
            filterUri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    /** For contacts/lookup or contacts/<id> URIs, extracts the aggregated contact ID. */
    private fun aggregatedContactId(uri: Uri, path: String): Long? = when {
        path.contains("/lookup/") ->
            ContactsContract.Contacts.lookupContact(contentResolver, uri)?.let {
                ContentUris.parseId(it)
            }
        else -> uri.lastPathSegment?.toLongOrNull()
    }

    /**
     * Converts an aggregated contact ID to a raw contact ID.
     * Picks the most visible raw contact: prefer STARRED, then lowest _ID.
     */
    private fun rawContactIdForAggregated(aggregatedId: Long): Long? {
        contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(aggregatedId.toString()),
            "${ContactsContract.RawContacts.STARRED} DESC, ${ContactsContract.RawContacts._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }
}
