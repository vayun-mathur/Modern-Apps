package com.vayunmathur.contacts.util
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDatabase
import com.vayunmathur.contacts.data.ContactEntity
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.ContactSearchEntity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.ManyManyMatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContactAccount(val name: String, val type: String)

class ContactViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = DataStoreUtils.getInstance(application)

    private val database = ContactDatabase.getInstance(application)
    private val contactDao = database.contactDao()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val hiddenAccounts: StateFlow<Set<String>> = dataStore.stringSetFlow("hidden_accounts")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val contacts: StateFlow<List<com.vayunmathur.contacts.data.Contact>> = combine(
        _searchQuery.flatMapLatest { query ->
            if (query.isBlank()) {
                contactDao.getContactsFlow()
            } else {
                contactDao.search("*$query*")
            }
        },
        hiddenAccounts
    ) { entities, hidden ->
        entities.map { it.toContact() }.filter { it.accountName !in hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    val allMatches: StateFlow<List<ManyManyMatching>> = contacts.map { contactList ->
        contactList.flatMap { contact ->
            contact.details.groups.map { membership ->
                ManyManyMatching(leftID = contact.id, rightID = membership.groupId, type = GROUP_MATCH_TYPE)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        const val GROUP_MATCH_TYPE = 101 // arbitrary type for contact-group matching
    }

    private val _accounts = MutableStateFlow<List<ContactAccount>>(emptyList())
    val accounts: StateFlow<List<ContactAccount>> = _accounts.asStateFlow()

    private val _lastSelectedAccount = MutableStateFlow<ContactAccount?>(null)
    val lastSelectedAccount: StateFlow<ContactAccount?> = _lastSelectedAccount.asStateFlow()

    val isCalendarSyncEnabled: StateFlow<Boolean> = dataStore.booleanFlow("calendar_sync_enabled")
        .stateIn(viewModelScope, SharingStarted.Eagerly, dataStore.getBoolean("calendar_sync_enabled", false))

    val showAccountLabels: StateFlow<Boolean> = dataStore.booleanFlow("show_account_labels")
        .stateIn(viewModelScope, SharingStarted.Eagerly, dataStore.getBoolean("show_account_labels", true))

    init {
        syncWithSystemContacts()
        loadAccounts()
        loadLastSelectedAccount()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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

    fun syncWithSystemContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val systemContacts = com.vayunmathur.contacts.data.Contact.getAllContacts(getApplication())
                val localContacts = contacts.value
                
                val toUpsert = systemContacts.map { ContactEntity.fromContact(it) }
                val searchEntities = systemContacts.map { contact ->
                    ContactSearchEntity(
                        rowid = contact.id,
                        displayName = contact.name.value,
                        nickname = contact.nickname.value,
                        phoneNumbers = contact.details.phoneNumbers.joinToString(" ") { it.number },
                        emails = contact.details.emails.joinToString(" ") { it.address },
                        notes = contact.details.notes.joinToString(" ") { it.content },
                        organization = contact.details.orgs.joinToString(" ") { it.company }
                    )
                }
                
                val systemIds = systemContacts.map { it.id }.toSet()
                val toDelete = localContacts.map { it.id }.filter { it !in systemIds }
                
                contactDao.syncContacts(toUpsert, toDelete, searchEntities)
                Log.d("ContactViewModel", "Sync complete: ${toUpsert.size} upserted, ${toDelete.size} deleted")
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error syncing contacts", e)
            }
        }
    }

    fun loadContacts() {
        syncWithSystemContacts()
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
                        try {
                            val name = it.getString(0) ?: ""
                            val type = it.getString(1) ?: ""
                            accountSet.add(ContactAccount(name, type))
                        } catch (e: Exception) {
                            Log.e("ContactViewModel", "Error reading account from cursor", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error querying raw contacts for accounts", e)
            }
            // Also include any accounts from DataStore that might not have contacts yet
            val savedAccounts = dataStore.getString("extra_accounts")?.split(",")?.filter { it.isNotEmpty() }?.mapNotNull { 
                try {
                    val parts = it.split("|")
                    ContactAccount(parts[0], parts.getOrElse(1) { "com.vayunmathur.contacts.local" })
                } catch (e: Exception) {
                    Log.e("ContactViewModel", "Error parsing extra account: $it", e)
                    null
                }
            } ?: emptyList()
            
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
            val current = dataStore.getString("extra_accounts") ?: ""
            val entry = "$name|$type"
            if (!current.contains(entry)) {
                val newValue = if (current.isEmpty()) entry else "$current,$entry"
                dataStore.setString("extra_accounts", newValue)
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
            contactDao.deleteContact(contact.id)
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

    fun addContactToGroup(contactId: Long, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
            }
            resolver.insert(ContactsContract.Data.CONTENT_URI, values)
            syncWithSystemContacts()
        }
    }

    fun removeContactFromGroup(contactId: Long, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val where = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?"
            val args = arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString())
            resolver.delete(ContactsContract.Data.CONTENT_URI, where, args)
            syncWithSystemContacts()
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
            syncWithSystemContacts()
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
            syncWithSystemContacts()
        }
    }

    fun getGroupsForContact(contactId: Long): Flow<List<ContactGroup>> {
        return combine(groups, allMatches) { groups, matches ->
            val groupIds = matches.filter { it.leftID == contactId && it.type == GROUP_MATCH_TYPE }.map { it.rightID }
            groups.filter { it.id in groupIds }
        }
    }

    fun getContactsForGroup(groupId: Long): Flow<List<Contact>> {
        return combine(contacts, allMatches) { contacts, matches ->
            val contactIds = matches.filter { it.rightID == groupId && it.type == GROUP_MATCH_TYPE }.map { it.leftID }
            contacts.filter { it.id in contactIds }
        }
    }

    fun getContactFlow(contactId: Long): Flow<Contact?> {
        return contacts.map { contacts -> contacts.find { it.id == contactId } }
    }

    fun loadContact(contactId: Long) {
        syncWithSystemContacts()
    }

    fun saveContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            val contactId = contact.id
            val details = contact.details
            val oldDetails = contacts.value.find { it.id == contactId }?.details ?: com.vayunmathur.contacts.data.ContactDetails.empty()
            contact.save(getApplication(), details, oldDetails)

            // Reload from system to ensure we have the latest (especially for new contacts)
            syncWithSystemContacts()
        }
    }
}