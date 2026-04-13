package com.vayunmathur.contacts.util
import android.app.Application
import android.content.ContentProviderOperation
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails

data class ContactAccount(val name: String, val type: String)

class ContactViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = DataStoreUtils.getInstance(application)

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    
    val hiddenAccounts: StateFlow<Set<String>> = dataStore.stringSetFlow("hidden_accounts")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val contacts: StateFlow<List<Contact>> = combine(_contacts, hiddenAccounts) { contacts, hidden ->
        contacts.filter { it.accountName !in hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _accounts = MutableStateFlow<List<ContactAccount>>(emptyList())
    val accounts: StateFlow<List<ContactAccount>> = _accounts.asStateFlow()

    init {
        loadContacts()
        loadAccounts()
    }

    fun loadContacts() {
        viewModelScope.launch {
            _contacts.value = Contact.getAllContacts(getApplication())
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
            val cursor = app.contentResolver.query(uri, projection, null, null, null)
            val accountSet = mutableSetOf<ContactAccount>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: ""
                    val type = it.getString(1) ?: ""
                    accountSet.add(ContactAccount(name, type))
                }
            }
            // Also include any accounts from DataStore that might not have contacts yet
            val savedAccounts = dataStore.getString("extra_accounts")?.split(",")?.filter { it.isNotEmpty() }?.map { 
                val parts = it.split("|")
                ContactAccount(parts[0], parts.getOrElse(1) { "com.vayunmathur.contacts.local" })
            } ?: emptyList()
            
            _accounts.value = (accountSet + savedAccounts).toList().sortedBy { it.name }
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

    fun deleteContact(contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            Contact.delete(getApplication(), contact)
            _contacts.value = _contacts.value.filter { it.id != contact.id }
        }
    }

    fun getContactFlow(contactId: Long): Flow<Contact?> {
        return contacts.map { contacts -> contacts.find { it.id == contactId } }
    }

    fun loadContact(contactId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedContact = Contact.getContact(getApplication(), contactId)
            withContext(Dispatchers.Main) {
                if (updatedContact != null) {
                    val index = _contacts.value.indexOfFirst { it.id == updatedContact.id }
                    if (index != -1) {
                        val newList = _contacts.value.toMutableList()
                        newList[index] = updatedContact
                        _contacts.value = newList
                    }
                }
            }
        }
    }

    fun saveContact(contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            val contactId = contact.id
            val details = contact.details
            val oldDetails = contacts.value.find { it.id == contactId }?.details ?: ContactDetails.empty()
            contact.save(getApplication(), details, oldDetails)

            if (contactId == 0L) {
                loadContacts()
            } else {
                loadContact(contactId)
            }
        }
    }
}