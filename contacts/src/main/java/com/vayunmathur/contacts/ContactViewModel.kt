package com.vayunmathur.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactViewModel(application: Application) : AndroidViewModel(application) {

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    init {
        loadContacts()
    }

    fun loadContacts() {
        viewModelScope.launch {
            _contacts.value = Contact.getAllContacts(getApplication())
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