@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlinx.coroutines.FlowPreview::class)

package com.vayunmathur.contacts.util
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Log
import androidx.collection.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
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
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

data class ContactAccount(val name: String, val type: String)

data class ContactGroupMembership(val contactId: Long, val groupId: Long)

class ContactViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = DataStoreUtils.getInstance(application)

    // Provider-backed in-memory contact list (no local DB). Populated by
    // syncFromSystem() from the system Contacts provider and refreshed via the
    // ContentObserver registered in init.
    private val _allContacts = MutableStateFlow<List<Contact>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val hiddenAccounts: StateFlow<Set<String>> = dataStore.stringSetFlow("hidden_accounts")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val contacts: StateFlow<List<com.vayunmathur.contacts.data.Contact>> = combine(
        _allContacts,
        _searchQuery,
        hiddenAccounts
    ) { all, query, hidden ->
        filterBySearch(all.filter { it.accountName !in hidden }, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun fetchGroups(): List<ContactGroup> {
        val resolver = getApplication<Application>().contentResolver
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE)
        val list = mutableListOf<ContactGroup>()
        resolver.query(uri, projection, "${ContactsContract.Groups.GROUP_VISIBLE} = 1 AND ${ContactsContract.Groups.DELETED} = 0", null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                list.add(ContactGroup(cursor.getLong(idIdx), cursor.getString(titleIdx) ?: "Unnamed"))
            }
        }
        return list.sortedBy { it.name }
    }

    val contactGroupMemberships: StateFlow<List<ContactGroupMembership>> = contacts.map { contactList ->
        contactList.flatMap { contact ->
            contact.details.groups.map { membership ->
                ContactGroupMembership(contactId = contact.id, groupId = membership.groupId)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _accounts = MutableStateFlow<List<ContactAccount>>(emptyList())
    val accounts: StateFlow<List<ContactAccount>> = _accounts.asStateFlow()

    private val _lastSelectedAccount = MutableStateFlow<ContactAccount?>(null)

    val isCalendarSyncEnabled: StateFlow<Boolean> = dataStore.booleanFlow("calendar_sync_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val showAccountLabels: StateFlow<Boolean> = dataStore.booleanFlow("show_account_labels")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Coalesces system-contact change notifications; collected with debounce so a
    // burst of provider writes triggers a single re-sync instead of one per change.
    private val syncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val observer = object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { syncTrigger.tryEmit(Unit) }
            }
            resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
            syncTrigger.tryEmit(Unit) // initial sync
            try {
                syncTrigger.debounce(500).collectLatest { syncFromSystem() }
            } finally {
                resolver.unregisterContentObserver(observer)
            }
        }
        loadAccounts()
        loadLastSelectedAccount()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * In-memory search over the provider-backed contact list. Splits the query
     * into whitespace-separated tokens; a contact matches when every token is a
     * case-insensitive substring of its searchable text (names, nicknames, phone
     * numbers, emails, notes, organizations). An empty query returns the full list.
     */
    private fun filterBySearch(list: List<Contact>, query: String): List<Contact> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return list
        return list.filter { contact ->
            val haystack = buildString {
                append(contact.details.names.joinToString(" ") { it.value }); append(' ')
                append(contact.details.nicknames.joinToString(" ") { it.nickname }); append(' ')
                append(contact.details.phoneNumbers.joinToString(" ") { it.number }); append(' ')
                append(contact.details.emails.joinToString(" ") { it.address }); append(' ')
                append(contact.details.notes.joinToString(" ") { it.content }); append(' ')
                append(contact.details.orgs.joinToString(" ") { it.company })
            }.lowercase()
            tokens.all { haystack.contains(it) }
        }
    }

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

    private suspend fun syncFromSystem() = withContext(Dispatchers.IO) {
        try {
            _allContacts.value = com.vayunmathur.contacts.data.Contact.getAllContacts(getApplication())
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error loading contacts", e)
        }
    }

    fun loadAccounts() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            )
            val accountSet = mutableSetOf<ContactAccount>()
            try {
                val cursor = app.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(0) ?: ""
                        val type = it.getString(1) ?: ""
                        accountSet.add(ContactAccount(name, type))
                    }
                }
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error querying raw contacts for accounts", e)
            }
            // Also include any accounts from DataStore that might not have contacts yet.
            // Stored as a string set of "name|type" entries to avoid the comma/pipe
            // corruption of the previous single comma-joined string.
            // Backward-compat: older builds stored these as a single comma-joined
            // String under "extra_accounts". Reading that String through a string-set
            // key throws, so migrate it once into the new set key, then blank the old one.
            val legacyAccounts = dataStore.getString("extra_accounts").orEmpty()
            if (legacyAccounts.isNotBlank()) {
                legacyAccounts.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    dataStore.addStringToSetIfAbsent("extra_accounts_set", it)
                }
                dataStore.setString("extra_accounts", "")
            }
            val savedAccounts = dataStore.stringSetFlow("extra_accounts_set").first().mapNotNull { entry ->
                val parts = entry.split("|")
                parts.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { name ->
                    ContactAccount(name, parts.getOrElse(1) { "com.vayunmathur.contacts.local" })
                }
            }

            _accounts.value = (accountSet + savedAccounts).toList().sortedBy { it.name }
        }
    }

    fun loadLastSelectedAccount() {
        val name = dataStore.getString("last_account_name")
        val type = dataStore.getString("last_account_type")
        _lastSelectedAccount.value = ContactAccount(name.orEmpty(), type.orEmpty())
    }

    fun setLastSelectedAccount(name: String, type: String) {
        viewModelScope.launch {
            dataStore.setString("last_account_name", name)
            dataStore.setString("last_account_type", type)
            _lastSelectedAccount.value = ContactAccount(name, type)
        }
    }

    fun setAccountVisibility(accountName: String, visible: Boolean) {
        if (visible) {
            dataStore.removeStringFromSet("hidden_accounts", accountName)
        } else {
            dataStore.addStringToSet("hidden_accounts", accountName)
        }
    }

    fun createAccount(name: String, type: String = "com.vayunmathur.contacts.local") {
        viewModelScope.launch {
            if (dataStore.addStringToSetIfAbsent("extra_accounts_set", "$name|$type")) {
                loadAccounts()
            }
        }
    }

    fun getContact(contactId: Long): Contact? {
        return contacts.value.find { it.id == contactId }
    }

    fun deleteContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            com.vayunmathur.contacts.data.Contact.delete(getApplication(), contact)
            if (isCalendarSyncEnabled.value) {
                CalendarSyncHelper.syncContact(getApplication(), contact.copy(details = contact.details.copy(dates = emptyList())))
            }
            // The system-contacts ContentObserver picks up the delete and re-syncs.
        }
    }

    // Groups Management
    fun addGroup(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Groups.TITLE, name)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                // Optionally add account info if needed
            }
            resolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        }
    }

    fun deleteGroup(groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId), null, null)
        }
    }

    fun addContactsToGroup(contactIds: List<Long>, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            contactIds.forEach { contactId ->
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    .build())
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }

    fun removeContactsFromGroup(contactIds: List<Long>, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            contactIds.forEach { contactId ->
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?", 
                        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()))
                    .build())
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }

    fun getContactsForGroup(groupId: Long): Flow<List<Contact>> {
        return combine(contacts, contactGroupMemberships) { contacts, memberships ->
            val contactIds = memberships.filter { it.groupId == groupId }.map { it.contactId }
            contacts.filter { it.id in contactIds }
        }
    }

    fun getContactFlow(contactId: Long): Flow<Contact?> {
        return contacts.map { contacts -> contacts.find { it.id == contactId } }
    }

    fun saveContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            val contactId = contact.id
            val details = contact.details
            val oldDetails = contacts.value.find { it.id == contactId }?.details ?: com.vayunmathur.contacts.data.ContactDetails.empty()
            contact.save(getApplication(), details, oldDetails)
            // The system-contacts ContentObserver picks up the write and re-syncs.
        }
    }

    // ---------------------------------------------------------------------
    // Base64 photo decode cache.
    //
    // Decoding Base64 + BitmapFactory.decodeByteArray on every recomposition
    // is wasteful: the same contact photo strings appear repeatedly in the
    // contact list, details page, and edit page. Cache the decoded Bitmaps
    // in a small LRU keyed by the raw Base64 string so we decode once.
    // ---------------------------------------------------------------------

    private val photoCache = LruCache<String, Bitmap>(32)

    /**
     * Returns the decoded [Bitmap] for the Base64-encoded contact photo, or
     * `null` if decoding fails. Decodes at most once per unique input string
     * across the entire app lifetime (subject to LRU eviction).
     */
    @Synchronized
    fun decodePhoto(base64: String): Bitmap? {
        photoCache.get(base64)?.let { return it }
        return try {
            val bytes = Base64.decode(base64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                photoCache.put(base64, it)
            }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error decoding contact photo", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // EditContactPage form draft state.
    //
    // Previously every form field lived in compose-local `remember { mutableStateOf(...) }`
    // / `mutableStateListOf(...)`. Hoisting it to the VM means the draft survives
    // configuration changes and recompositions, and lets the build-and-save logic
    // live alongside the rest of the contact write path.
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
        val photo: Photo? = null,
        val birthday: LocalDate? = null,
        val accountName: String = "",
        val accountType: String = "",
        val phoneNumbers: List<PhoneNumber> = emptyList(),
        val emails: List<Email> = emptyList(),
        val dates: List<Event> = emptyList(),
        val addresses: List<Address> = emptyList(),
        val groupMemberships: List<GroupMembership> = emptyList(),
    )

    private val _editDraft = MutableStateFlow<ContactDraft?>(null)
    val editDraft: StateFlow<ContactDraft?> = _editDraft.asStateFlow()

    /** Original contact loaded into the current draft, if any. */
    private var editingOriginal: Contact? = null
    /** Tracks which contactId the draft was initialized for. `null` = new contact. */
    private var editingContactId: Long? = null
    /** True once a draft has been initialized at all (distinguishes "new contact" from "uninitialized"). */
    private var editingInitialized: Boolean = false

    /**
     * Initializes the edit-form draft from an existing contact (if [contactId] is non-null)
     * and/or prefilled fields from a navigation intent. No-op if a draft for the same
     * [contactId] already exists — so the user's unsaved edits survive rotation.
     */
    fun initEditDraft(
        contactId: Long?,
        prefillName: String? = null,
        prefillPhone: String? = null,
        prefillEmail: String? = null,
        prefillCompany: String? = null,
        prefillNotes: String? = null,
    ) {
        if (editingInitialized && editingContactId == contactId && _editDraft.value != null) return
        val contact = contactId?.let {
            getContact(it) ?: Contact.getContact(getApplication(), it)
        }
        val details = contact?.details
        editingOriginal = contact
        editingContactId = contactId
        editingInitialized = true
        _editDraft.value = ContactDraft(
            namePrefix = contact?.name?.namePrefix ?: "",
            firstName = contact?.name?.firstName ?: prefillName ?: "",
            middleName = contact?.name?.middleName ?: "",
            lastName = contact?.name?.lastName ?: "",
            nameSuffix = contact?.name?.nameSuffix ?: "",
            company = contact?.org?.company ?: prefillCompany ?: "",
            noteContent = contact?.note?.content ?: prefillNotes ?: "",
            nickname = contact?.nickname?.nickname ?: "",
            photo = contact?.photo,
            birthday = contact?.birthday?.startDate,
            accountName = contact?.accountName ?: _lastSelectedAccount.value?.name ?: "",
            accountType = contact?.accountType ?: _lastSelectedAccount.value?.type ?: "",
            phoneNumbers = (details?.phoneNumbers ?: emptyList()).let { list ->
                if (list.isEmpty() && prefillPhone != null)
                    listOf(PhoneNumber(0, prefillPhone, CDKPhone.TYPE_MOBILE))
                else list
            },
            emails = (details?.emails ?: emptyList()).let { list ->
                if (list.isEmpty() && prefillEmail != null)
                    listOf(Email(0, prefillEmail, CDKEmail.TYPE_HOME))
                else list
            },
            dates = details?.dates ?: emptyList(),
            addresses = details?.addresses ?: emptyList(),
            groupMemberships = details?.groups ?: emptyList(),
        )
    }

    /** Applies [transform] to the current draft, if any. */
    fun updateEditDraft(transform: (ContactDraft) -> ContactDraft) {
        val current = _editDraft.value ?: return
        _editDraft.value = transform(current)
    }

    /** Clears the in-progress draft and forgets which contact was being edited. */
    fun clearEditDraft() {
        _editDraft.value = null
        editingOriginal = null
        editingContactId = null
        editingInitialized = false
    }

    /**
     * Decodes the picked image URI off the main thread, scales it to 500x500,
     * Base64-encodes it, and updates [editDraft]'s photo. Errors are logged
     * and the draft is left unchanged so the picker callback never blocks the
     * UI thread (large gallery photos can take 100+ ms to decode).
     */
    fun setEditDraftPhotoFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val scaled = if (bitmap.width != 1024 || bitmap.height != 1024) {
                Bitmap.createScaledBitmap(bitmap, 1024, 1024, true)
            } else bitmap
            val baos = okio.Buffer()
            scaled.compress(Bitmap.CompressFormat.JPEG, 100, baos.outputStream())
            val encoded = Base64.encode(baos.readByteArray())
            updateEditDraft { draft ->
                val newPhoto = draft.photo?.withValue(encoded)
                    ?: com.vayunmathur.contacts.data.Photo(0, encoded)
                draft.copy(photo = newPhoto)
            }
        }
    }

    /**
     * Builds a [Contact] from the current draft (merging IDs from the originally
     * loaded contact where applicable) and persists it via [saveContact], then
     * clears the draft.
     */
    fun saveEditDraft() {
        val draft = _editDraft.value ?: return
        val original = editingOriginal
        val birthdayId = original?.birthday?.id ?: 0L
        val datesWithoutBirthday = draft.dates.filter { it.type != CDKEvent.TYPE_BIRTHDAY }.toMutableList()
        draft.birthday?.let { bday ->
            datesWithoutBirthday += Event(birthdayId, bday, CDKEvent.TYPE_BIRTHDAY)
        }
        val details = ContactDetails(
            phoneNumbers = draft.phoneNumbers,
            emails = draft.emails,
            addresses = draft.addresses,
            dates = datesWithoutBirthday,
            photos = listOfNotNull(draft.photo),
            names = listOf(
                Name(
                    original?.name?.id ?: 0,
                    draft.namePrefix,
                    draft.firstName,
                    draft.middleName,
                    draft.lastName,
                    draft.nameSuffix
                )
            ),
            orgs = listOf(Organization(original?.org?.id ?: 0, draft.company)),
            notes = listOf(Note(original?.note?.id ?: 0, draft.noteContent)),
            nicknames = listOf(
                Nickname(
                    original?.nickname?.id ?: 0,
                    draft.nickname,
                    CDKNickname.TYPE_DEFAULT
                )
            ),
            groups = draft.groupMemberships
        )
        val newContact = original?.copy(details = details) ?: Contact(
            id = 0,
            accountType = draft.accountType.ifEmpty { null },
            accountName = draft.accountName.ifEmpty { null },
            isFavorite = false,
            details = details
        )
        saveContact(newContact)
        clearEditDraft()
    }
}