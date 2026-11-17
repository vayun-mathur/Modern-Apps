package com.vayunmathur.contacts.platform
import com.vayunmathur.contacts.data.VcfUtils

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.SaveOutcome
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VCF import operations, kept out of ContactViewModel so the class stays
 * under the function cap (see TooManyFunctions). Called as viewModel.*.
 */
/** Parses every [uris] off the main thread and exposes the result via [ContactViewModel.parsedVcfContacts]. */
fun ContactViewModel.parseVcfUris(uris: List<android.net.Uri>) {
    if (uris.isEmpty()) {
        parsedVcfContactsState.value = emptyList()
        return
    }
    val app = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
        val allContacts = mutableListOf<Contact>()
        uris.forEach { uri ->
            parseSingleVcfUri(app, uri, allContacts)
        }
        parsedVcfContactsState.value = allContacts
    }
}

private fun ContactViewModel.parseSingleVcfUri(
    app: Application,
    uri: android.net.Uri,
    allContacts: MutableList<Contact>,
) {
    try {
        app.contentResolver.openInputStream(uri)?.use { input ->
            allContacts.addAll(
                VcfUtils.parseContacts(input) { names -> resolveImportGroups(app, names) }
            )
        }
    } catch (e: java.io.FileNotFoundException) {
        Log.error("ContactViewModel", "Error parsing VCF file: $uri", e)
    } catch (e: SecurityException) {
        Log.error("ContactViewModel", "Error parsing VCF file: $uri", e)
    } catch (e: java.io.IOException) {
        Log.error("ContactViewModel", "Error parsing VCF file: $uri", e)
    }
}

/**
 * Resolves vCard CATEGORIES names to GroupMemberships for import, creating
 * a visible group for any name the provider does not already have. Without
 * this the parser's resolver defaulted to empty, so group memberships the
 * exporter writes as CATEGORIES were silently dropped on re-import.
 * Per-name failures are logged and skipped so one bad name cannot lose
 * the rest (the parser also guards the call as a whole).
 */
private fun resolveImportGroups(
    app: Application,
    names: List<String>,
): List<com.vayunmathur.contacts.data.GroupMembership> {
    val resolver = app.contentResolver
    val existing = queryExistingImportGroups(resolver) ?: return emptyList()
    val result = mutableListOf<com.vayunmathur.contacts.data.GroupMembership>()
    for (name in names) {
        val membership = resolveSingleImportGroup(resolver, name.trim(), existing)
        if (membership != null) result += membership
    }
    return result
}

/** Existing visible groups by lowercased title, or null when the provider query fails. */
private fun queryExistingImportGroups(
    resolver: android.content.ContentResolver,
): MutableMap<String, Long>? {
    val existing = mutableMapOf<String, Long>()
    try {
        resolver.query(
            android.provider.ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.Groups._ID,
                android.provider.ContactsContract.Groups.TITLE,
            ),
            "${android.provider.ContactsContract.Groups.DELETED} = 0",
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Groups._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIdx)?.trim().orEmpty()
                if (title.isNotEmpty()) existing[title.lowercase()] = cursor.getLong(idIdx)
            }
        }
    } catch (e: SecurityException) {
        Log.error("ContactViewModel", "Error reading groups for VCF import", e)
        return null
    } catch (e: IllegalArgumentException) {
        Log.error("ContactViewModel", "Error reading groups for VCF import", e)
        return null
    } catch (e: android.database.SQLException) {
        Log.error("ContactViewModel", "Error reading groups for VCF import", e)
        return null
    }
    return existing
}

/** Membership for one trimmed name: the existing group, or a newly created visible one. */
private fun resolveSingleImportGroup(
    resolver: android.content.ContentResolver,
    trimmed: String,
    existing: MutableMap<String, Long>,
): com.vayunmathur.contacts.data.GroupMembership? {
    if (trimmed.isEmpty()) return null
    existing[trimmed.lowercase()]?.let { return com.vayunmathur.contacts.data.GroupMembership(0, it) }
    val newId = createImportGroup(resolver, trimmed) ?: return null
    existing[trimmed.lowercase()] = newId
    return com.vayunmathur.contacts.data.GroupMembership(0, newId)
}

/** Creates a visible group titled [trimmed], returning its id or null when creation fails. */
private fun createImportGroup(
    resolver: android.content.ContentResolver,
    trimmed: String,
): Long? {
    return try {
        val values = android.content.ContentValues().apply {
            put(android.provider.ContactsContract.Groups.TITLE, trimmed)
            put(android.provider.ContactsContract.Groups.GROUP_VISIBLE, 1)
        }
        val createdUri = resolver.insert(
            android.provider.ContactsContract.Groups.CONTENT_URI, values
        )
        createdUri?.lastPathSegment?.toLongOrNull()
    } catch (e: SecurityException) {
        Log.error("ContactViewModel", "Error creating group for VCF import", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.error("ContactViewModel", "Error creating group for VCF import", e)
        null
    } catch (e: android.database.SQLException) {
        Log.error("ContactViewModel", "Error creating group for VCF import", e)
        null
    }
}

/** Clears any parsed-VCF state held in the VM (called when the import screen dismisses). */
fun ContactViewModel.clearParsedVcf() {
    parsedVcfContactsState.value = null
}

/**
 * Bulk-imports the previously parsed [contacts] into the account with [accountName] and [accountType].
 * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure),
 * with a user-facing error when the provider refused the target account (#916), else null.
 */
fun ContactViewModel.importVcfContacts(
    contacts: List<Contact>,
    accountName: String,
    accountType: String,
    onDone: (error: String?) -> Unit = {},
) {
    val app = getApplication<Application>()
    viewModelScope.launch {
        val rejected = withContext(Dispatchers.IO) {
            // Broad catch is deliberate: bulk import crosses the contacts
            // provider, SIM ADN and VCF parsing, and must report failure
            // via onDone rather than crashing the import screen.
            @Suppress("TooGenericExceptionCaught")
            try {
                if (isSimAccountType(accountType)) {
                    importVcfToSim(app, contacts, accountName)
                    // Refresh unified list
                    withContext(Dispatchers.Main) { loadContacts() }
                    false
                } else {
                    importVcfToProvider(app, contacts, accountName, accountType)
                }
            } catch (e: Exception) {
                Log.error("ContactViewModel", "Error importing contacts", e)
                false
            }
        }
        loadContacts()
        onDone(if (rejected) cloudDefaultRejectionMessage() else null)
    }
}

private suspend fun ContactViewModel.importVcfToSim(
    app: Application,
    contacts: List<Contact>,
    accountName: String,
) {
    val subId = accountName.toIntOrNull()
    contacts.forEach { contact ->
        val name = contact.name.value.trim().ifEmpty {
            contact.details.phoneNumbers.firstOrNull()?.number ?: ""
        }
        val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
        val email = contact.details.emails.firstOrNull()?.address?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (name.isNotBlank() || number.isNotBlank()) {
            SimContactsDataSource.insertSimContact(app, name, number, email, subId)
        }
    }
}

/**
 * Saves each contact into the account. Returns true when the provider refused the account
 * because the system default is a cloud account (#916); every contact targets the same
 * account, so the first refusal stops the import instead of failing each one in turn.
 */
private fun importVcfToProvider(
    app: Application,
    contacts: List<Contact>,
    accountName: String,
    accountType: String,
): Boolean {
    for (contact in contacts) {
        val toSave = contact.copy(
            accountName = accountName,
            accountType = accountType
        )
        val outcome = toSave.saveWithOutcome(app, toSave.details, ContactDetails.empty())
        if (outcome == SaveOutcome.REJECTED_CLOUD_DEFAULT) return true
    }
    return false
}
