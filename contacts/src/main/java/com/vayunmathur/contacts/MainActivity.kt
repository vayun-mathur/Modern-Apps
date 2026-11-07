package com.vayunmathur.contacts

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.ClipData
import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import androidx.core.content.IntentCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKNote
import com.vayunmathur.contacts.data.CDKOrg
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKSName
import com.vayunmathur.contacts.data.CDKStructuredPostal
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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

class MainActivity : MaActivity() {
    private val importUris = mutableStateOf<List<String>>(emptyList())
    private val externalRoute = mutableStateOf<Route?>(null)

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

    /**
     * Parses everything an ACTION_INSERT-style intent can carry into a [ContactPrefill].
     *
     * Reads the scalar [ContactsContract.Intents.Insert] extras via [Intent.getCharSequenceExtra]
     * (dialers/call logs pass values as `CharSequence`/`Spannable`, so `getStringExtra` returns
     * null for them) plus the [ContactsContract.Intents.Insert.DATA] `ArrayList<ContentValues>`.
     */
    private fun buildInsertPrefill(intent: Intent): ContactPrefill {
        fun text(key: String): String? =
            intent.getCharSequenceExtra(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val phones = collectPrefillPhones(intent, ::text)
        val emails = collectPrefillEmails(intent, ::text)
        val postals = collectPrefillPostals(intent, ::text)
        val names = collectPrefillNames(::text)
        applyPrefillDataRows(intent, phones, emails, postals, names)

        return ContactPrefill(
            names.name,
            names.company,
            names.notes,
            names.nickname,
            phones,
            emails,
            postals,
        )
    }

    private fun collectPrefillPhones(
        intent: Intent,
        text: (String) -> String?,
    ): MutableList<PrefillValue> {
        val phones = mutableListOf<PrefillValue>()
        val (phoneType, phoneLabel) = readType(intent, Insert.PHONE_TYPE)
        text(Insert.PHONE)?.let { phones += PrefillValue(it, phoneType, phoneLabel) }
        text(Insert.SECONDARY_PHONE)?.let { phones += PrefillValue(it) }
        text(Insert.TERTIARY_PHONE)?.let { phones += PrefillValue(it) }
        return phones
    }

    private fun collectPrefillEmails(
        intent: Intent,
        text: (String) -> String?,
    ): MutableList<PrefillValue> {
        val emails = mutableListOf<PrefillValue>()
        val (emailType, emailLabel) = readType(intent, Insert.EMAIL_TYPE)
        text(Insert.EMAIL)?.let { emails += PrefillValue(it, emailType, emailLabel) }
        text(Insert.SECONDARY_EMAIL)?.let { emails += PrefillValue(it) }
        text(Insert.TERTIARY_EMAIL)?.let { emails += PrefillValue(it) }
        return emails
    }

    private fun collectPrefillPostals(
        intent: Intent,
        text: (String) -> String?,
    ): MutableList<PrefillValue> {
        val postals = mutableListOf<PrefillValue>()
        val (postalType, postalLabel) = readType(intent, Insert.POSTAL_TYPE)
        text(Insert.POSTAL)?.let { postals += PrefillValue(it, postalType, postalLabel) }
        return postals
    }

    private class PrefillNames(
        var name: String? = null,
        var company: String? = null,
        var notes: String? = null,
        var nickname: String? = null,
    )

    private fun collectPrefillNames(
        text: (String) -> String?,
    ): PrefillNames = PrefillNames(
        name = text(Insert.NAME),
        company = text(Insert.COMPANY),
        notes = text(Insert.NOTES),
    )

    private fun applyPrefillDataRows(
        intent: Intent,
        phones: MutableList<PrefillValue>,
        emails: MutableList<PrefillValue>,
        postals: MutableList<PrefillValue>,
        names: PrefillNames,
    ) {
        // Insert.DATA: caller-provided rows, one ContentValues per data kind (typed).
        val dataRows =
            IntentCompat.getParcelableArrayListExtra(intent, Insert.DATA, ContentValues::class.java)
        dataRows?.forEach { cv ->
            fun value(key: String) = cv.getAsString(key)?.trim()?.takeIf { it.isNotEmpty() }
            when (cv.getAsString(ContactsContract.Data.MIMETYPE)) {
                CDKPhone.CONTENT_ITEM_TYPE ->
                    applyPrefillPhoneRow(cv, ::value, phones)
                CDKEmail.CONTENT_ITEM_TYPE ->
                    applyPrefillEmailRow(cv, ::value, emails)
                CDKStructuredPostal.CONTENT_ITEM_TYPE ->
                    applyPrefillPostalRow(cv, ::value, postals)
                CDKSName.CONTENT_ITEM_TYPE ->
                    if (names.name == null) names.name = value(CDKSName.DISPLAY_NAME)
                CDKOrg.CONTENT_ITEM_TYPE ->
                    if (names.company == null) names.company = value(CDKOrg.COMPANY)
                CDKNote.CONTENT_ITEM_TYPE ->
                    if (names.notes == null) names.notes = value(CDKNote.NOTE)
                CDKNickname.CONTENT_ITEM_TYPE ->
                    if (names.nickname == null) names.nickname = value(CDKNickname.NAME)
            }
        }
    }

    private fun applyPrefillPhoneRow(
        cv: ContentValues,
        value: (String) -> String?,
        phones: MutableList<PrefillValue>,
    ) {
        val number = value(CDKPhone.NUMBER) ?: return
        phones += PrefillValue(number, cv.getAsInteger(CDKPhone.TYPE), cv.getAsString(CDKPhone.LABEL))
    }

    private fun applyPrefillEmailRow(
        cv: ContentValues,
        value: (String) -> String?,
        emails: MutableList<PrefillValue>,
    ) {
        val address = value(CDKEmail.ADDRESS) ?: return
        emails += PrefillValue(address, cv.getAsInteger(CDKEmail.TYPE), cv.getAsString(CDKEmail.LABEL))
    }

    private fun applyPrefillPostalRow(
        cv: ContentValues,
        value: (String) -> String?,
        postals: MutableList<PrefillValue>,
    ) {
        val formatted = value(CDKStructuredPostal.FORMATTED_ADDRESS) ?: return
        postals += PrefillValue(
            formatted,
            cv.getAsInteger(CDKStructuredPostal.TYPE),
            cv.getAsString(CDKStructuredPostal.LABEL),
        )
    }

    private fun resolveContactId(uri: Uri?): Long? {
        if (uri == null) return null
        val path = uri.path ?: return null

        // For raw_contacts URIs, the last segment is already a raw contact ID
        if (path.startsWith("/raw_contacts/") || path.contains("/raw_contacts")) {
            return uri.lastPathSegment?.toLongOrNull()
        }

        // A phone_lookup URI identifies a contact by number, not by ID: resolve
        // the number through PhoneLookup before the generic ID extraction below.
        if (path.contains("/phone_lookup/")) {
            val number = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
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
                if (!cursor.moveToFirst()) return null
                val phoneLookupId = cursor.getLong(0)
                // Convert aggregated contact ID to raw contact ID (same as below).
                contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts._ID),
                    "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
                    arrayOf(phoneLookupId.toString()),
                    "${ContactsContract.RawContacts.STARRED} DESC, ${ContactsContract.RawContacts._ID} ASC"
                )?.use { rawCursor ->
                    if (rawCursor.moveToFirst()) {
                        return rawCursor.getLong(0)
                    }
                }
            }
            return null
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Navigation(
    viewModel: ContactViewModel,
    initialRoute: Route? = null,
    onExit: () -> Unit = {},
    onImportClear: () -> Unit = {},
) {
    // Deep-link handling: tab routes (ContactsList, GroupsList, Settings) now live inside the
    // pager host (Route.Main). A groups deep-link (Route.GroupsList(groupId)) is remembered
    // so the pager can expand that group; other tab routes just collapse to the host.
    val groupsExpandId = (initialRoute as? Route.GroupsList)?.expandGroupId
    val startRoute: Route = initialRoute?.toHostRoute() ?: Route.Main
    val backStack = rememberNavBackStack<Route>(startRoute)
    // Route.Settings is now inside the pager host (Route.Main). The App Info entry-point
    // is handled by starting the pager on its Settings page; pushing Route.Main again would
    // just duplicate the host, so do not use openSettingsIfRequested with a tab route.
    // Keep the helper for non-tab deep-links only: if the app was launched from App Info
    // we ensure the back stack is at least the host (already is) and the pager shows Settings.
    val activity = LocalActivity.current
    val launchedFromAppInfo = activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES
    val startOnSettings = launchedFromAppInfo || initialRoute is Route.Settings

    LaunchedEffect(initialRoute) {
        // Only push non-tab routes (detail/edit/import etc.) that are not already the host.
        // Tab routes are represented by the pager host and must not be pushed as separate entries
        // (GroupsList with an expand arg is handled via groupsExpandId above).
        val pending = initialRoute
        if (pending != null && pending.shouldPushOnto(backStack)) {
            backStack.add(pending)
        }
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
        startOnSettings = startOnSettings,
        goBack = ::goBack,
    )
}

@Composable
private fun NavGraphEntries(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    tabsCallbacks: TabsCallbacks,
    groupsExpandId: Long?,
    startOnSettings: Boolean,
    goBack: () -> Unit,
) {
    MainNavigation(backStack) {
        entry<Route.Main>(metadata = ListPage()) {
            ContactsTabs(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = tabsCallbacks.onContactClick,
                onAddContactClick = tabsCallbacks.onAddContactClick,
                initialGroupsExpandId = groupsExpandId,
                startOnSettings = startOnSettings,
            )
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

/** Tab routes collapse to the pager host; every other route pushes as-is. */
private fun Route.toHostRoute(): Route =
    when (this) {
        is Route.ContactsList, is Route.GroupsList, is Route.Settings -> Route.Main
        else -> this
    }

/** True for a non-tab deep-link that is not already on top of [backStack]. */
private fun Route?.shouldPushOnto(backStack: NavBackStack<Route>): Boolean {
    if (this == null || this == Route.Main) return false
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

/**
 * The three bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * ContactDetail, EditContact, InsertOrEditContact and ImportVcf are pushed on top
 * of this host as ordinary routes. Groups deep-links pass their expand arg through.
 */
@Composable
private fun ContactsTabs(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    onContactClick: (com.vayunmathur.contacts.data.Contact) -> Unit,
    onAddContactClick: () -> Unit,
    initialGroupsExpandId: Long? = null,
    startOnSettings: Boolean = false,
) {
    val startPage = when {
        startOnSettings -> 2
        initialGroupsExpandId != null -> 1
        else -> 0
    }
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { 3 })
    // Contacts (page 0) is the home tab: system back from Groups/Settings returns
    // there instead of exiting the app. Back from Contacts falls through to the
    // nav host, which exits when it is the only entry.
    val scope = rememberCoroutineScope()
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
    val tabs = listOf(
        PagerTab(stringResource(UiR.string.contacts), { IconPerson() }) {
            ContactList(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = onContactClick,
                onAddContactClick = onAddContactClick,
            )
        },
        PagerTab(stringResource(R.string.groups), { IconGroup() }) {
            GroupsPage(viewModel, backStack, initialGroupsExpandId)
        },
        PagerTab(stringResource(com.vayunmathur.library.ui.R.string.settings), { IconSettings() }) {
            SettingsPage(viewModel, backStack)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}

sealed interface Route: NavKey {
    @Serializable
    object Main : Route

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
        val prefill: ContactPrefill? = null
    ) : Route

    @Serializable
    data class InsertOrEditContact(
        val prefill: ContactPrefill? = null
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

    @Serializable
    data class ImportVcf(val uris: List<String>) : Route
}
