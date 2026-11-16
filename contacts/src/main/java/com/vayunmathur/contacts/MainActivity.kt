package com.vayunmathur.contacts

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
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.loadAccounts
import com.vayunmathur.contacts.util.setEditDraftPhotoFromBitmap
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Navigation(
    viewModel: ContactViewModel,
    initialRoute: Route? = null,
    onExit: () -> Unit = {},
    onImportClear: () -> Unit = {},
) {
    // Deep-link handling: tab routes (ContactsList, GroupsList, Settings) are
    // real sibling destinations. A tab deep link is the start destination
    // itself; a groups deep-link's expand arg is remembered separately so the
    // start route can be the plain GroupsList tab (keeping it selected in the
    // bottom bar) while GroupsPage still expands that group. The App Info
    // entry point starts on the Settings tab.
    val groupsExpandId = (initialRoute as? Route.GroupsList)?.expandGroupId
    val activity = LocalActivity.current
    val launchedFromAppInfo = activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES
    val startOnSettings = launchedFromAppInfo || initialRoute is Route.Settings
    val startTab: Route = when {
        startOnSettings -> Route.Settings
        initialRoute is Route.GroupsList -> Route.GroupsList()
        initialRoute is Route.Settings -> Route.Settings
        initialRoute is Route.ContactsList -> Route.ContactsList
        groupsExpandId != null -> Route.GroupsList()
        else -> Route.ContactsList
    }
    // Non-tab deep links (detail/edit/import) keep their previous shape: the
    // route itself is the root, with the bottom bar showing no tab selected.
    val startRoute: Route = when (initialRoute) {
        is Route.ContactsList, is Route.GroupsList, is Route.Settings, null -> startTab
        else -> initialRoute
    }
    val backStack = rememberNavBackStack<Route>(startRoute)

    LaunchedEffect(initialRoute) {
        // Warm deep links: a tab route resets the stack to that sibling (the
        // groups expand arg travels via groupsExpandId above); a non-tab route
        // pushes if it is not already on top.
        val pending = initialRoute ?: return@LaunchedEffect
        when (pending) {
            is Route.ContactsList -> backStack.reset(Route.ContactsList)
            is Route.GroupsList -> backStack.reset(Route.GroupsList())
            is Route.Settings -> backStack.reset(Route.Settings)
            else -> if (pending.shouldPushOnto(backStack)) backStack.add(pending)
        }
    }

    // Contacts is the home tab: system back from Groups/Settings returns
    // there instead of exiting the app (the pager's old behaviour). Back from
    // Contacts falls through to the nav host, which exits at the root.
    BackHandler(enabled = backStack.last() is Route.GroupsList || backStack.last() is Route.Settings) {
        backStack.reset(Route.ContactsList)
    }

    // Clear import URIs when leaving ImportVcf screen
    LaunchedEffect(backStack.backStack) {
        if (backStack.backStack.lastOrNull() !is Route.ImportVcf) {
            onImportClear()
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

    val tabsCallbacks = rememberTabsCallbacks(backStack)
    NavGraphEntries(
        viewModel = viewModel,
        backStack = backStack,
        tabsCallbacks = tabsCallbacks,
        groupsExpandId = groupsExpandId,
        goBack = ::goBack,
    )
}

@Composable
private fun NavGraphEntries(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    tabsCallbacks: TabsCallbacks,
    groupsExpandId: Long?,
    goBack: () -> Unit,
) {
    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf<RouteTab<Route>>(
            RouteTab(Route.ContactsList, stringResource(UiR.string.contacts)) { IconPerson() },
            RouteTab(Route.GroupsList(), stringResource(R.string.groups)) { IconGroup() },
            RouteTab(Route.Settings, stringResource(com.vayunmathur.library.ui.R.string.settings)) { IconSettings() },
        ),
    ) {
    MainNavigation(backStack) {
        entry<Route.ContactsList>(metadata = ListPage() + SiblingPage()) {
            ContactList(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = tabsCallbacks.onContactClick,
                onAddContactClick = tabsCallbacks.onAddContactClick,
            )
        }
        entry<Route.GroupsList>(metadata = ListPage() + SiblingPage()) { key ->
            GroupsPage(viewModel, backStack, key.expandGroupId ?: groupsExpandId)
        }
        entry<Route.Settings>(metadata = ListPage() + SiblingPage()) {
            SettingsPage(viewModel, backStack)
        }

        entry<Route.ContactDetail>(metadata = ListDetailPage() + MorphPage()) { key ->
            ContactDetailsPage(
                viewModel = viewModel,
                contactId = key.contactId,
                onBack = { goBack() },
                onEdit = { id -> backStack.add(Route.EditContact(id)) },
                onDelete = {
                    // Show the delete confirmation dialog using the contact id and name
                    val contact = viewModel.getContact(key.contactId)
                    backStack.add(
                        Route.EventDeleteConfirmDialog(key.contactId, contact?.name?.value),
                    )
                },
                showBackButton = true,
            )
        }
        entry<Route.EditContact>(metadata = ListDetailPage() + MorphPage()) { key ->
            EditContactPage(backStack, viewModel, key, onExit = { goBack() })
        }

        entry<Route.InsertOrEditContact>(metadata = ListDetailPage()) { key ->
            InsertOrEditContactScreen(
                viewModel = viewModel,
                backStack = backStack,
                insertOrEditRoute = key,
                onExit = goBack,
            )
        }

        entry<Route.AddAccountDialog>(metadata = DialogPage()) {
            AddAccountDialog(viewModel) { backStack.pop() }
        }

        entry<Route.EventDatePickerDialog>(metadata = DialogPage()) { key ->
            EventDatePickerDialog(key.id, key.initialDate) { backStack.pop() }
        }

        entry<Route.EventDeleteConfirmDialog>(metadata = DialogPage()) { key ->
            EventDeleteConfirmEntry(viewModel, backStack, key)
        }

        entry<Route.AddToGroupDialog>(metadata = DialogPage()) { key ->
            AddToGroupDialog(viewModel, key.contactIds) { backStack.pop() }
        }

        entry<Route.CropPhoto>(metadata = ListDetailPage()) { key ->
            CropPhotoEntry(backStack, viewModel, key)
        }

        entry<Route.ImportVcf> { key ->
            ImportVcfScreen(viewModel, backStack, key.uris)
        }
    }
    }
}

@Composable
private fun EventDeleteConfirmEntry(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    key: Route.EventDeleteConfirmDialog,
) {
    EventDeleteConfirmDialog(
        key.contactId,
        key.contactName,
        viewModel,
        onConfirm = {
            // After confirming deletion, pop the dialog and the detail page
            backStack.pop()
            backStack.pop()
        },
        onDismiss = {
            // Only close the dialog
            backStack.pop()
        },
    )
}


/** True for a non-tab deep-link that is not already on top of [backStack]. */
private fun Route?.shouldPushOnto(backStack: NavBackStack<Route>): Boolean {
    if (this == null) return false
    if (this is Route.ContactsList || this is Route.GroupsList || this is Route.Settings) {
        return false
    }
    return backStack.last() != this
}

private class TabsCallbacks(
    val onContactClick: (com.vayunmathur.contacts.data.Contact) -> Unit,
    val onAddContactClick: () -> Unit,
)

@Composable
private fun rememberTabsCallbacks(
    backStack: NavBackStack<Route>,
): TabsCallbacks = remember(backStack) {
    TabsCallbacks(
        onContactClick = { contact ->
            val last = backStack.last()
            val isDetail = last is Route.ContactDetail || last is Route.EditContact
            if (isDetail) {
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
        },
    )
}

@Composable
private fun CropPhotoEntry(
    backStack: NavBackStack<Route>,
    viewModel: ContactViewModel,
    key: Route.CropPhoto,
) {
    val decodedUri = java.net.URLDecoder.decode(key.uri, "UTF-8")
    CropPhotoScreen(
        uri = decodedUri,
        onCropComplete = { bitmap ->
            viewModel.setEditDraftPhotoFromBitmap(bitmap)
            backStack.pop()
        },
        onCancel = { backStack.pop() },
    )
}

/** Navigation destinations for the contacts host: sibling tab routes, the rest push onto the back stack. */
sealed interface Route: NavKey {
    @Serializable
    /** Contacts tab destination (sibling route, the home tab). */
    object ContactsList : Route

    @Serializable
    /** Groups tab destination (sibling route), optionally expanding the group with [expandGroupId]. */
    data class GroupsList(val expandGroupId: Long? = null) : Route

    @Serializable
    /** Dialog destination adding [contactIds] to a chosen group. */
    data class AddToGroupDialog(val contactIds: List<Long>) : Route

    @Serializable
    /** Detail page destination for the contact with [contactId]. */
    data class ContactDetail(val contactId: Long) : Route

    @Serializable
    /** Editor destination for [contactId] (null creates a contact), seeded by [prefill] when present. */
    data class EditContact(
        val contactId: Long?,
        val prefill: ContactPrefill? = null
    ) : Route

    @Serializable
    /** Insert-or-edit destination seeded by [prefill], used by dialer-style intents. */
    data class InsertOrEditContact(
        val prefill: ContactPrefill? = null
    ) : Route

    @Serializable
    /** Date-picker dialog destination identified by [id], starting at [initialDate]. */
    data class EventDatePickerDialog(val id: String, val initialDate: LocalDate?): Route

    @Serializable
    /** Delete-confirmation dialog destination for [contactId], labelled with [contactName]. */
    data class EventDeleteConfirmDialog(val contactId: Long, val contactName: String?): Route

    @Serializable
    /** Settings tab destination (sibling route). */
    object Settings : Route

    @Serializable
    /** Dialog destination for creating a new contacts account. */
    object AddAccountDialog : Route

    @Serializable
    /** Photo-crop destination for the image at [uri]. */
    data class CropPhoto(val uri: String) : Route

    @Serializable
    /** vCard import destination for the files at [uris]. */
    data class ImportVcf(val uris: List<String>) : Route
}
