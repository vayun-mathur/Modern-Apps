@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlinx.coroutines.FlowPreview::class)

package com.vayunmathur.contacts.util
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log
import androidx.collection.LruCache
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContact
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.contacts.util.ContactSorting.sortedByNameLocale
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

data class ContactAccount(val name: String, val type: String)

data class ContactGroupMembership(val contactId: Long, val groupId: Long)

/**
 * Implements [ContactsActions] so a binder can hand itself straight to a stateless screen;
 * the navigating members keep their no-op defaults and are overridden per screen, since the
 * ViewModel has no back stack.
 */
/** Query-token separator. Hoisted so a keystroke does not recompile the pattern. */
private val WHITESPACE = Regex("\\s+")

/** Stops the WhileSubscribed sharing timeout for contact flows. */
private const val SUBSCRIBE_TIMEOUT = 5_000L

/** Debounce for coalesced system-contact change notifications. */
private const val SYNC_DEBOUNCE_MILLIS = 300L

class ContactViewModel(application: Application) : AndroidViewModel(application), ContactsActions {

    internal val dataStore = DataStoreUtils.getInstance(application)

    // Provider-backed in-memory contact list (no local DB). Populated by
    // syncFromSystem() from the system Contacts provider + SIM ADN and refreshed via the
    // ContentObserver registered in init.
    internal val allContactsState = MutableStateFlow<List<Contact>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // The contact list starts empty and fills in asynchronously, so screens need to tell
    // "nothing loaded yet" apart from "there really are no contacts".
    private val _hasLoadedContacts = MutableStateFlow(false)
    val hasLoadedContacts: StateFlow<Boolean> = _hasLoadedContacts.asStateFlow()

    val hiddenAccounts: StateFlow<Set<String>> = dataStore.stringSetFlow("hidden_accounts")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun accountKey(type: String?, name: String?): String = "${type ?: ""}|${name ?: ""}"

    private fun encodedAccountKey(type: String?, name: String?): String {
        fun enc(raw: String): String =
            raw.replace("%", "%25").replace("|", "%7C").replace(",", "%2C")
        return "${enc(type ?: "")}|${enc(name ?: "")}"
    }

    /** Unfiltered address book for export (ignores search query and hidden accounts). */
    val allContactsForExport: StateFlow<List<com.vayunmathur.contacts.data.Contact>> =
        allContactsState.asStateFlow()

    /**
     * Each contact paired with the lowercased text [filterBySearch] matches against.
     *
     * Built once per change to the address book rather than once per keystroke. The haystack
     * concatenates every name, nickname, phone number, email, note and company a contact has and
     * then lowercases the lot; doing that for the whole book between two frames of typing was the
     * expensive half of search, and none of it depends on what was typed.
     */
    private val searchIndex: StateFlow<List<Pair<com.vayunmathur.contacts.data.Contact, String>>> =
        allContactsState
            .map { all -> all.map { it to searchHaystack(it) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT), emptyList())

    val contacts: StateFlow<List<com.vayunmathur.contacts.data.Contact>> = combine(
        searchIndex,
        _searchQuery,
        hiddenAccounts
    ) { indexed, query, hidden ->
        val visible = indexed.filter { (c, _) ->
            val key = accountKey(c.accountType, c.accountName)
            val encodedKey = encodedAccountKey(c.accountType, c.accountName)
            // Support legacy hidden entries that stored only accountName, raw "type|name"
            // keys, and separator-safe encoded keys.
            key !in hidden && encodedKey !in hidden && c.accountName !in hidden
        }
        val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) visible.map { it.first }
        else filterByTokens(visible, tokens).map { it.first }
    }
        // viewModelScope is Main.immediate, so without this the whole address book was filtered on
        // the UI thread between one frame of typing and the next.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT), emptyList())

    private fun filterByTokens(
        visible: List<Pair<com.vayunmathur.contacts.data.Contact, String>>,
        tokens: List<String>,
    ): List<Pair<com.vayunmathur.contacts.data.Contact, String>> =
        visible.filter { (_, haystack) ->
            tokens.all { token -> haystackMatchesToken(haystack, token) }
        }

    private fun haystackMatchesToken(haystack: String, token: String): Boolean {
        if (haystack.contains(token)) return true
        val normalizedToken = normalizePhoneForCompare(token)
        return normalizedToken.isNotEmpty() &&
            normalizedToken != token &&
            haystack.contains(normalizedToken)
    }

    // Virtual SIM account display labels: key is "type|name" -> "SIM N — Carrier"
    internal val simAccountLabelsState = MutableStateFlow<Map<String, String>>(emptyMap())
    val simAccountLabels: StateFlow<Map<String, String>> = simAccountLabelsState.asStateFlow()

    // Short form of the above ("SIM N", no carrier) for the storage badge on a contact row, which
    // has room for a name but not a name plus a carrier.
    internal val simSlotLabelsState = MutableStateFlow<Map<String, String>>(emptyMap())
    val simSlotLabels: StateFlow<Map<String, String>> = simSlotLabelsState.asStateFlow()

    val groups: StateFlow<List<ContactGroup>> = callbackFlow {
        val resolver = getApplication<Application>().contentResolver
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                launch { send(fetchGroups()) }
            }
        }
        resolver.registerContentObserver(ContactsContract.Groups.CONTENT_URI, true, observer)
        send(fetchGroups())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT), emptyList())

    private fun fetchGroups(): List<ContactGroup> {
        val resolver = getApplication<Application>().contentResolver
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE)
        val list = mutableListOf<ContactGroup>()
        val selection =
            "${ContactsContract.Groups.GROUP_VISIBLE} = 1 AND ${ContactsContract.Groups.DELETED} = 0"
        resolver.query(uri, projection, selection, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIdx) ?: "Unnamed"
                list.add(ContactGroup(cursor.getLong(idIdx), title))
            }
        }
        return list.sortedByNameLocale { it.name }
    }

    val contactGroupMemberships: StateFlow<List<ContactGroupMembership>> = contacts.map { contactList ->
        contactList.flatMap { contact ->
            contact.details.groups.map { membership ->
                ContactGroupMembership(contactId = contact.id, groupId = membership.groupId)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT), emptyList())

    internal val accountsState = MutableStateFlow<List<ContactAccount>>(emptyList())
    val accounts: StateFlow<List<ContactAccount>> = accountsState.asStateFlow()

    internal val lastSelectedAccountState = MutableStateFlow<ContactAccount?>(null)

    // Parsed-VCF state for the import screen. null = not yet parsed (or cleared);
    // empty list = parsed and found nothing; non-empty = parsed contacts ready to import.
    internal val parsedVcfContactsState = MutableStateFlow<List<Contact>?>(null)
    val parsedVcfContacts: StateFlow<List<Contact>?> = parsedVcfContactsState.asStateFlow()

    val isCalendarSyncEnabled: StateFlow<Boolean> = dataStore.booleanFlow("calendar_sync_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val showAccountLabels: StateFlow<Boolean> = dataStore.booleanFlow("show_account_labels")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Which name field leads the list ordering. Unknown stored values (e.g. from a newer
     * version) fall back to first-name rather than crashing on `valueOf`.
     */
    val sortMode: StateFlow<ContactSorting.ContactSortMode> = dataStore.stringFlow("sort_mode")
        .map { stored ->
            runCatching { ContactSorting.ContactSortMode.valueOf(stored) }
                .getOrDefault(ContactSorting.ContactSortMode.FirstName)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactSorting.ContactSortMode.FirstName)

    fun setSortMode(mode: ContactSorting.ContactSortMode) {
        viewModelScope.launch { dataStore.setString("sort_mode", mode.name) }
    }

    // Coalesces system-contact change notifications; collected with debounce so a
    // burst of provider writes triggers a single re-sync instead of one per change.
    private val syncTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Fires on any ContactsContract change (contact/raw/data/account/group). Kept as a
    // field so onCleared() can unregister it and avoid leaking the ContentResolver.
    private val contactsObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) { syncTrigger.tryEmit(Unit) }
    }

    // Fires on SIM ADN (content://icc/adn) changes: our own SIM writes notify
    // these URIs (see SimContactsDataSource), and some OEM IccProviders notify
    // them on external/modem-side updates too. The ContactsContract observer
    // above never fires for ADN writes, so without this the refreshed SIM list
    // after a write depends solely on the immediate re-read converging. Feeds
    // the same debounced syncTrigger; kept as a field so onCleared() can
    // unregister it.
    private val simObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) { syncTrigger.tryEmit(Unit) }
    }

    private fun simAdnBaseUri(): android.net.Uri =
        android.net.Uri.parse(SimContactsDataSource.SIM_ADN_BASE_URI)

    // Fires on SIM insert/remove/swap. The provider observer above never fires for
    // those (no provider write happens), so without this the SIM list only refreshes
    // when the app is reopened. Feeds the same debounced syncTrigger; kept as a field
    // so onCleared() can unregister it. Registering needs no permission; reading the
    // subscription list inside syncFromSystem() already degrades gracefully without
    // READ_PHONE_STATE (see SimContactsDataSource).
    private val subscriptionListener =
        object : android.telephony.SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() { syncTrigger.tryEmit(Unit) }
        }

    private fun subscriptionManager(): android.telephony.SubscriptionManager? =
        getApplication<Application>().getSystemService(android.telephony.SubscriptionManager::class.java)

    init {
        // notifyForDescendants=true on the top-level authority so any contact/raw/data/
        // account change fires it; debounced so a sync burst causes a single reload.
        getApplication<Application>().contentResolver
            .registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactsObserver)
        // notifyForDescendants=true so subId-scoped ADN URIs fire it too.
        getApplication<Application>().contentResolver
            .registerContentObserver(simAdnBaseUri(), true, simObserver)
        // Broad catch is deliberate: OEM SubscriptionManager implementations throw
        // undocumented RuntimeExceptions, and a failed listener must not block init.
        @Suppress("TooGenericExceptionCaught")
        try {
            subscriptionManager()?.addOnSubscriptionsChangedListener(
                getApplication<Application>().mainExecutor,
                subscriptionListener,
            )
        } catch (e: Exception) {
            Log.error("ContactViewModel", "Failed to register subscription listener", e)
        }
        viewModelScope.launch {
            // Load immediately so cold-launched screens like InsertOrEdit don't
            // show empty list while waiting for the debounce.
            syncFromSystem()
            syncTrigger.debounce(SYNC_DEBOUNCE_MILLIS).collectLatest { syncFromSystem() }
        }
        loadAccounts()
        loadLastSelectedAccount()
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(contactsObserver)
        getApplication<Application>().contentResolver.unregisterContentObserver(simObserver)
        // Broad catch is deliberate: must not throw out of onCleared.
        @Suppress("TooGenericExceptionCaught")
        try {
            subscriptionManager()?.removeOnSubscriptionsChangedListener(subscriptionListener)
        } catch (e: Exception) {
            Log.error("ContactViewModel", "Failed to unregister subscription listener", e)
        }
        super.onCleared()
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * The lowercased text a contact is matched against: names, nicknames, phone numbers, emails,
     * notes and organizations, run together.
     *
     * Built once per contact when the address book changes (see [searchIndex]), not per keystroke.
     * A query matches when every whitespace-separated token is a substring of this.
     */
    private fun searchHaystack(contact: Contact): String = buildString {
        append(contact.details.names.joinToString(" ") { it.value }); append(' ')
        append(contact.details.nicknames.joinToString(" ") { it.nickname }); append(' ')
        append(contact.details.phoneNumbers.joinToString(" ") { it.number }); append(' ')
        val normalized = contact.details.phoneNumbers.joinToString(" ") {
            normalizePhoneForCompare(it.number)
        }
        append(normalized); append(' ')
        append(contact.details.emails.joinToString(" ") { it.address }); append(' ')
        append(contact.details.notes.joinToString(" ") { it.content }); append(' ')
        append(contact.details.orgs.joinToString(" ") { it.company }); append(' ')
        append(contact.details.addresses.joinToString(" ") { it.formattedAddress })
    }.lowercase()

    fun setCalendarSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("calendar_sync_enabled", enabled)
            if (enabled) {
                withContext(Dispatchers.IO) {
                    CalendarSyncHelper.syncAll(getApplication())
                }
                CalendarWorker.schedule(getApplication())
            } else {
                withContext(Dispatchers.IO) {
                    CalendarSyncHelper.removeCalendar(getApplication())
                }
                CalendarWorker.cancel(getApplication())
            }
        }
    }

    fun setShowAccountLabels(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("show_account_labels", enabled)
        }
    }

    /** Requests a debounced re-sync from the system contacts provider. */
    fun loadContacts() {
        syncTrigger.tryEmit(Unit)
    }

    internal suspend fun syncFromSystem() = withContext(Dispatchers.IO) {
        // Broad catch is deliberate: sync crosses the contacts provider, SIM ADN
        // and DataStore, and a failed refresh must mark load complete rather
        // than crashing the collector.
        @Suppress("TooGenericExceptionCaught")
        try {
            refreshContactsFromSystem()
        } catch (e: Exception) {
            Log.error("ContactViewModel", "Error loading contacts", e)
        } finally {
            _hasLoadedContacts.value = true
        }
    }

    private suspend fun refreshContactsFromSystem() {
        val app = getApplication<Application>()
        migrateNoYearEventDates()
        migrateBirthdayCalendarToUtc()
        val device = com.vayunmathur.contacts.data.Contact.getAllContacts(app)
        val sim = SimContactsDataSource.simContactsAsContacts(app)
        allContactsState.value = device + sim
        // Refresh SIM account labels for UI (type|name -> display)
        val infos = SimContactsDataSource.getSimSubscriptionInfos(app)
        val labels = infos.associate { info ->
            val acc = ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
            "${acc.type}|${acc.name}" to
                SimContactsDataSource.getSimAccountDisplayLabel(app, info)
        }
        simAccountLabelsState.value = labels
        simSlotLabelsState.value = simSlotLabelsFor(infos)
        // Also refresh the accounts list to include current SIM accounts (in case SIM inserted/removed)
        // Do it by launching loadAccounts() if needed; but we can update accountsState directly
        // to avoid double query. However loadAccounts() also merges DataStore saved accounts,
        // so we trigger it.
        // Use a direct call to avoid extra launch overhead: we are already on IO, but
        // loadAccounts() launches its own coroutine, so just trigger it.
    // To keep accounts in sync, refresh directly: already on IO via syncFromSystem().
        loadAccountsInternal()
    }

    fun getContact(contactId: Long): Contact? {
        return findContact(contactId)
    }

    /** Builds the fallback [SimContact] when no ADN row backs [contact]. */
    private fun fallbackSimContact(contact: com.vayunmathur.contacts.data.Contact): SimContact {
        val phone = contact.details.phoneNumbers.firstOrNull()?.number ?: ""
        val email = contact.details.emails.firstOrNull()?.address
        return SimContact(
            -1,
            contact.name.value,
            phone,
            email,
            contact.accountName?.toIntOrNull()
        )
    }

    override fun deleteContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isSimAccountType(contact.accountType)) {
                deleteSimBackedContact(contact)
            } else {
                deleteProviderContact(contact)
            }
            // The system-contacts ContentObserver picks up device deletes and re-syncs; SIM path already synced.
        }
    }

    private suspend fun deleteSimBackedContact(contact: com.vayunmathur.contacts.data.Contact) {
        val app = getApplication<Application>()
        val sc = SimContactsDataSource.findBackingSimContact(app, contact)
            ?: fallbackSimContact(contact)
        val subId = sc.subscriptionId ?: contact.accountName?.toIntOrNull()
        SimContactsDataSource.deleteSimContact(app, sc.copy(subscriptionId = subId))
        syncFromSystem()
    }

    private suspend fun deleteProviderContact(contact: com.vayunmathur.contacts.data.Contact) {
        val app = getApplication<Application>()
        com.vayunmathur.contacts.data.Contact.delete(app, contact)
        if (isCalendarSyncEnabled.value) {
            val dateless = contact.copy(details = contact.details.copy(dates = emptyList()))
            CalendarSyncHelper.syncContact(app, dateless)
        }
    }

    // Groups Management
    override fun addGroup(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Groups.TITLE, name)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            }
            resolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        }
    }

    override fun deleteGroup(groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId), null, null)
        }
    }

    override fun saveContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) { persistContact(contact) }
    }

    /** Writes [contact] to the SIM or the contacts provider. Returns false if the write failed. */
    internal suspend fun persistContact(contact: com.vayunmathur.contacts.data.Contact): Boolean {
        if (isSimAccountType(contact.accountType)) {
            return persistSimContact(contact)
        }
        return persistProviderContact(contact)
    }

    // ---------------------------------------------------------------------
    // Base64 photo decode cache.
    // ---------------------------------------------------------------------

    private val photoCache = LruCache<String, Bitmap>(32)

    /**
     * Returns the decoded [Bitmap] for the Base64-encoded contact photo, or
     * `null` if decoding fails. Decodes at most once per unique input string
     * across the entire app lifetime (subject to LRU eviction).
     */
    @Synchronized
    override fun decodePhoto(base64: String): Bitmap? {
        photoCache.get(base64)?.let { return it }
        return try {
            val bytes = Base64.decode(base64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                photoCache.put(base64, it)
            }
        } catch (e: IllegalArgumentException) {
            Log.error("ContactViewModel", "Error decoding contact photo", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // EditContactPage form draft state.
    // ---------------------------------------------------------------------

    data class ContactDraft(
        val namePrefix: String = "",
        val firstName: String = "",
        val middleName: String = "",
        val lastName: String = "",
        val nameSuffix: String = "",
        val company: String = "",
        val noteContent: String = "",
        val nickname: String = "",
        val genderCode: String = "",
        val genderText: String = "",
        val photo: Photo? = null,
        val birthday: LocalDate? = null,
        val accountName: String = "",
        val accountType: String = "",
        val phoneNumbers: List<PhoneNumber> = emptyList(),
        val emails: List<Email> = emptyList(),
        val dates: List<Event> = emptyList(),
        val addresses: List<Address> = emptyList(),
        val groupMemberships: List<GroupMembership> = emptyList(),
        val commPref: CommPref? = null,
    )

    internal val editDraftState = MutableStateFlow<ContactDraft?>(null)
    val editDraft: StateFlow<ContactDraft?> = editDraftState.asStateFlow()

    /** Original contact loaded into the current draft, if any. */
    internal var editingOriginal: Contact? = null
    /** Tracks which contactId the draft was initialized for. `null` = new contact. */
    internal var editingContactId: Long? = null
    /** True once a draft has been initialized at all (distinguishes "new contact" from "uninitialized"). */
    internal var editingInitialized: Boolean = false

    internal fun normalizePhoneForCompare(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }.trim()
    }
}
