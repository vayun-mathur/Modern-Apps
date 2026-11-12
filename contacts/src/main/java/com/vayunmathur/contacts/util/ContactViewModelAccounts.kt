package com.vayunmathur.contacts.util

import android.app.Application
import android.content.ContentProviderOperation
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.NewContactAccountPolicy
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.contacts.data.isSimAccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WHERE clause (+ selection args) matching a RawContacts account. An empty
 * type/name is treated as NULL-or-empty in the provider, so device-local
 * accounts (whose ACCOUNT_TYPE/ACCOUNT_NAME may be stored as NULL) are matched.
 */
internal fun ContactViewModel.accountSelection(type: String, name: String): Pair<String, Array<String>> {
    val args = ArrayList<String>()
    val typeClause = if (type.isEmpty()) {
        "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} = '')"
    } else {
        args.add(type)
        "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
    }
    val nameClause = if (name.isEmpty()) {
        "(${ContactsContract.RawContacts.ACCOUNT_NAME} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_NAME} = '')"
    } else {
        args.add(name)
        "${ContactsContract.RawContacts.ACCOUNT_NAME} = ?"
    }
    return "$typeClause AND $nameClause" to args.toTypedArray()
}

/** Percent-encode account key components: `%` first, then `|` and `,`. */
internal fun encodeAccountComponent(raw: String): String =
    raw.replace("%", "%25").replace("|", "%7C").replace(",", "%2C")

internal fun decodeAccountComponent(encoded: String): String =
    encoded.replace("%7C", "|", ignoreCase = true)
        .replace("%2C", ",", ignoreCase = true)
        .replace("%25", "%")

internal fun encodeExtraAccountEntry(name: String, type: String): String =
    "${encodeAccountComponent(name)}|${encodeAccountComponent(type)}"

internal fun encodeHiddenAccountKey(type: String, name: String): String =
    "${encodeAccountComponent(type)}|${encodeAccountComponent(name)}"

/** Decoded account if [entry] is a clean encoded "name|type" entry (exactly one separator and round-trips). */
internal fun decodeExtraAccountEntryClean(entry: String): ContactAccount? {
    if (entry.count { it == '|' } != 1) return null
    val rawName = entry.substringBefore("|")
    val rawType = entry.substringAfter("|")
    if (rawName.isEmpty()) return null
    val name = decodeAccountComponent(rawName)
    val type = decodeAccountComponent(rawType)
    if (encodeExtraAccountEntry(name, type) != entry) return null
    return ContactAccount(name, type)
}

/** Decoded (type, name) if [entry] is a clean encoded "type|name" hidden key. */
internal fun decodeHiddenKeyClean(entry: String): Pair<String, String>? {
    if (entry.count { it == '|' } != 1) return null
    val rawType = entry.substringBefore("|")
    val rawName = entry.substringAfter("|")
    if (rawType.isEmpty() && rawName.isEmpty()) return null
    val type = decodeAccountComponent(rawType)
    val name = decodeAccountComponent(rawName)
    if (encodeHiddenAccountKey(type, name) != entry) return null
    return type to name
}

internal suspend fun ContactViewModel.loadAccountsInternal() {
    val app = getApplication<Application>()
    val accountSet = queryProviderAccounts(app)
    migrateLegacyAccounts()
    val savedAccounts = loadSavedAccounts()
    migrateHiddenAccountKeys()
    refreshSimAccounts(app)
    val restriction = NewContactAccountPolicy.resolve(defaultAccountSource)
    newContactRestrictionState.value = restriction
    // The cloud default is where new contacts now go, so offer it even when it holds
    // no contacts yet (it would otherwise be missing from every account picker).
    val cloud = restriction?.cloudAccount?.let { ContactAccount(it.name, it.type) }
    sortAccountsIntoState((accountSet + savedAccounts + simAccountsOf(app) + listOfNotNull(cloud)).toList())
}

/**
 * Re-reads the system default account for new contacts (Android 16+, #916) so the
 * pickers and new drafts reflect a change made in system settings since the last sync.
 */
internal suspend fun ContactViewModel.refreshNewContactRestriction() {
    newContactRestrictionState.value = withContext(Dispatchers.IO) {
        NewContactAccountPolicy.resolve(defaultAccountSource)
    }
}

/**
 * User-facing reason a write was refused because the system default account for new
 * contacts is a cloud account (#916). Refreshes the restriction first, so the pickers
 * grey out the refused accounts and the message names the current default.
 */
internal suspend fun ContactViewModel.cloudDefaultRejectionMessage(): String {
    refreshNewContactRestriction()
    val app = getApplication<Application>()
    val cloud = newContactRestrictionState.value?.cloudAccount?.name
    return if (cloud.isNullOrBlank()) {
        app.getString(R.string.cloud_default_rejected_generic)
    } else {
        app.getString(R.string.cloud_default_rejected, cloud)
    }
}

/**
 * Account a new contact starts in: the last one picked, unless the provider would refuse
 * new contacts there (device or provider SIM account while the system default is a cloud
 * account, #916), in which case the cloud default account.
 */
internal fun ContactViewModel.newContactDefaultAccount(): ContactAccount {
    val last = lastSelectedAccountState.value ?: ContactAccount("", "")
    val restriction = newContactRestrictionState.value ?: return last
    if (!restriction.blocks(last.name, last.type)) return last
    return ContactAccount(restriction.cloudAccount.name, restriction.cloudAccount.type)
}

private suspend fun ContactViewModel.queryProviderAccounts(
    app: Application,
): MutableSet<ContactAccount> {
    val accountSet = mutableSetOf<ContactAccount>()
    try {
        // Tombstoned raw contacts keep their account columns, so without this filter an
        // account whose every contact has been deleted stays in the account list forever.
        val cursor = app.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            ),
            "${ContactsContract.RawContacts.DELETED} = 0",
            null,
            null,
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: ""
                val type = it.getString(1) ?: ""
                accountSet.add(ContactAccount(name, type))
            }
        }
    } catch (e: android.database.SQLException) {
        Log.error("ContactViewModel", "Error querying raw contacts for accounts", e)
    } catch (e: SecurityException) {
        Log.error("ContactViewModel", "Error querying raw contacts for accounts", e)
    } catch (e: IllegalArgumentException) {
        Log.error("ContactViewModel", "Error querying raw contacts for accounts", e)
    }
    return accountSet
}

// Virtual SIM accounts
private suspend fun ContactViewModel.refreshSimAccounts(app: Application) {
    val simInfos = SimContactsDataSource.getSimSubscriptionInfos(app)
    val simLabels = simInfos.associate { info ->
        val acc = ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
        "${acc.type}|${acc.name}" to SimContactsDataSource.getSimAccountDisplayLabel(app, info)
    }
    simAccountLabelsState.value = simLabels
    simSlotLabelsState.value = simSlotLabelsFor(simInfos)
}

private fun simAccountsOf(app: Application): List<ContactAccount> {
    val simInfos = SimContactsDataSource.getSimSubscriptionInfos(app)
    return simInfos.map { info ->
        ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
    }
}

private fun ContactViewModel.sortAccountsIntoState(all: List<ContactAccount>) {
    // Sort: SIM accounts by display label, others by name
    val collator = ContactSorting.collator()
    accountsState.value = all.sortedWith(
        compareBy(collator) { acc -> sortKeyFor(acc) }
    )
}

private fun ContactViewModel.sortKeyFor(acc: ContactAccount): String {
    if (acc.type == SIM_ACCOUNT_TYPE) {
        return simAccountLabelsState.value["${acc.type}|${acc.name}"] ?: acc.name
    }
    return acc.name
}

fun ContactViewModel.loadAccounts() {
    viewModelScope.launch(Dispatchers.IO) {
        loadAccountsInternal()
    }
}

/**
 * Reloads the persisted last-selected account (see [setLastSelectedAccount]) into
 * memory. The in-memory state does not survive a process kill, so this must run
 * before a new-contact draft is seeded — otherwise a cold start falls back to the
 * on-device default even though the choice is still in DataStore. Safe to re-call.
 */
fun ContactViewModel.loadLastSelectedAccount() {
    val name = dataStore.getString("last_account_name")
    val type = dataStore.getString("last_account_type")
    lastSelectedAccountState.value = ContactAccount(name.orEmpty(), type.orEmpty())
}

fun ContactViewModel.setLastSelectedAccount(name: String, type: String) {
    viewModelScope.launch {
        dataStore.setString("last_account_name", name)
        dataStore.setString("last_account_type", type)
        lastSelectedAccountState.value = ContactAccount(name, type)
    }
}

fun ContactViewModel.setAccountVisibility(account: ContactAccount, visible: Boolean) {
    val key = encodeHiddenAccountKey(account.type, account.name)
    val legacyRawKey = "${account.type}|${account.name}"
    if (visible) {
        dataStore.removeStringFromSet("hidden_accounts", key)
        if (legacyRawKey != key) {
            dataStore.removeStringFromSet("hidden_accounts", legacyRawKey)
        }
        // Also clear legacy entry if it exists (migration)
        dataStore.removeStringFromSet("hidden_accounts", account.name)
    } else {
        dataStore.addStringToSet("hidden_accounts", key)
        if (legacyRawKey != key) {
            dataStore.removeStringFromSet("hidden_accounts", legacyRawKey)
        }
    }
}

// Backward-compatible overload used by old call sites that only knew name
fun ContactViewModel.setAccountVisibility(accountName: String, visible: Boolean) {
    // Try to resolve type from current accounts list
    val matched = accountsState.value.firstOrNull { it.name == accountName }
    if (matched != null) {
        setAccountVisibility(matched, visible)
    } else {
        // Fallback: treat as legacy name-only key
        if (visible) dataStore.removeStringFromSet("hidden_accounts", accountName)
        else dataStore.addStringToSet("hidden_accounts", accountName)
    }
}

fun ContactViewModel.createAccount(
    name: String,
    type: String = LOCAL_ACCOUNT_TYPE,
    onComplete: (() -> Unit)? = null,
) {
    viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        val collides = accountsState.value.any {
            it.type == type && it.name.equals(trimmed, ignoreCase = true)
        }
        if (collides) {
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
            return@launch
        }
        if (dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(trimmed, type))) {
            loadAccounts()
        }
        withContext(Dispatchers.Main) {
            onComplete?.invoke()
        }
    }
}

/**
 * Renames a local (on-device) account. Guards that [account] is a local account and that
 * [newName] is non-blank and does not collide with an existing account of the same type.
 * Re-points every RawContact stored under the old name to [newName] and migrates the
 * DataStore references (saved-accounts set, hidden-accounts key, last-selected account).
 * [onResult] is invoked on the main thread with success and an optional error message key.
 */
fun ContactViewModel.renameLocalAccount(
    account: ContactAccount,
    newName: String,
    onResult: ((Boolean, String?) -> Unit)? = null,
) {
    viewModelScope.launch {
        val trimmed = newName.trim()
        val guardError = renameGuardError(account, trimmed)
        if (guardError != null) {
            val (ok, code) = guardError
            withContext(Dispatchers.Main) { onResult?.invoke(ok, code) }
            return@launch
        }
        withContext(Dispatchers.IO) {
            renameLocalAccountInternal(account, trimmed)
        }
        loadAccounts()
        loadContacts()
        withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
    }
}

/** Returns (ok, code) when the rename must be rejected, null when it may proceed. */
private fun ContactViewModel.renameGuardError(
    account: ContactAccount,
    trimmed: String,
): Pair<Boolean, String?>? {
    if (!isLocalAccountType(account.type)) return false to "not_local"
    if (isDefaultLocalAccount(account.name, account.type)) return false to "not_local"
    if (trimmed.isEmpty()) return false to "blank"
    if (trimmed == account.name) return true to null
    val collides = accountsState.value.any {
        it.type == account.type &&
            it.name.equals(trimmed, ignoreCase = true) &&
            it.name != account.name
    }
    if (collides) return false to "collision"
    return null
}

// Broad catch is deliberate: provider batch writes fail with OEM-specific
// RuntimeExceptions beyond the checked batch exceptions, and a rename
// must log rather than crash the caller.
@Suppress("TooGenericExceptionCaught")
private suspend fun ContactViewModel.renameLocalAccountInternal(
    account: ContactAccount,
    trimmed: String,
) {
    try {
        val resolver = getApplication<Application>().contentResolver
        val ops = ArrayList<ContentProviderOperation>()
        val (sel, args) = accountSelection(account.type, account.name)
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(sel, args)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, trimmed)
                .build()
        )
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    } catch (e: android.content.OperationApplicationException) {
        Log.error("ContactViewModel", "Error renaming local account", e)
    } catch (e: android.os.RemoteException) {
        Log.error("ContactViewModel", "Error renaming local account", e)
    } catch (e: Exception) {
        Log.error("ContactViewModel", "Error renaming local account", e)
    }
    migrateRenamedAccountRefs(account, trimmed)
}

private suspend fun ContactViewModel.migrateRenamedAccountRefs(
    account: ContactAccount,
    trimmed: String,
) {
    // Migrate the saved-accounts label so an empty account (no contacts) is renamed too.
    dataStore.removeStringFromSet(
        "extra_accounts_set",
        encodeExtraAccountEntry(account.name, account.type)
    )
    dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
    dataStore.addStringToSetIfAbsent(
        "extra_accounts_set",
        encodeExtraAccountEntry(trimmed, account.type)
    )
    // Migrate the hidden-accounts visibility key ("type|name").
    val hidden = dataStore.getStringSetAwait("hidden_accounts")
    val oldHiddenKey = encodeHiddenAccountKey(account.type, account.name)
    val legacyRawHiddenKey = "${account.type}|${account.name}"
    if (oldHiddenKey in hidden || legacyRawHiddenKey in hidden) {
        dataStore.removeStringFromSet("hidden_accounts", oldHiddenKey)
        if (legacyRawHiddenKey != oldHiddenKey) {
            dataStore.removeStringFromSet("hidden_accounts", legacyRawHiddenKey)
        }
        dataStore.addStringToSet(
            "hidden_accounts",
            encodeHiddenAccountKey(account.type, trimmed)
        )
    }
    // Migrate last-selected (the implicit default save location).
    if (dataStore.getString("last_account_type") == account.type &&
        dataStore.getString("last_account_name") == account.name
    ) {
        dataStore.setString("last_account_name", trimmed)
        lastSelectedAccountState.value = ContactAccount(trimmed, account.type)
    }
}

/**
 * Deletes a local (on-device) account and all of its contacts. Guards that [account] is a
 * local account. Hard-deletes every RawContact for the account (via the
 * CALLER_IS_SYNCADAPTER URI param, since the local account has no sync adapter) and clears
 * the DataStore references. [onResult] is invoked on the main thread.
 */
fun ContactViewModel.deleteLocalAccount(
    account: ContactAccount,
    onResult: ((Boolean, String?) -> Unit)? = null,
) {
    viewModelScope.launch {
        if (!isLocalAccountType(account.type)) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
            return@launch
        }
        if (isDefaultLocalAccount(account.name, account.type)) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
            return@launch
        }
        withContext(Dispatchers.IO) {
            deleteLocalAccountRows(account)
            clearDeletedAccountRefs(account)
        }
        loadAccounts()
        loadContacts()
        withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
    }
}

// Broad catch is deliberate: provider deletes fail with OEM-specific
// RuntimeExceptions beyond the narrow delete exceptions, and an account
// delete must log rather than crash the caller.
@Suppress("TooGenericExceptionCaught")
private suspend fun ContactViewModel.deleteLocalAccountRows(account: ContactAccount) {
    try {
        val resolver = getApplication<Application>().contentResolver
        val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
        val (sel, args) = accountSelection(account.type, account.name)
        resolver.delete(uri, sel, args)
    } catch (e: android.database.SQLException) {
        Log.error("ContactViewModel", "Error deleting local account", e)
    } catch (e: SecurityException) {
        Log.error("ContactViewModel", "Error deleting local account", e)
    } catch (e: Exception) {
        Log.error("ContactViewModel", "Error deleting local account", e)
    }
}

private suspend fun ContactViewModel.clearDeletedAccountRefs(account: ContactAccount) {
    dataStore.removeStringFromSet(
        "extra_accounts_set",
        encodeExtraAccountEntry(account.name, account.type)
    )
    dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
    dataStore.removeStringFromSet(
        "hidden_accounts",
        encodeHiddenAccountKey(account.type, account.name)
    )
    dataStore.removeStringFromSet("hidden_accounts", "${account.type}|${account.name}")
    // Legacy name-only hidden key.
    dataStore.removeStringFromSet("hidden_accounts", account.name)
    // If this account was the default save location, reset to on-device.
    if (dataStore.getString("last_account_type") == account.type &&
        dataStore.getString("last_account_name") == account.name
    ) {
        dataStore.setString("last_account_name", "")
        dataStore.setString("last_account_type", "")
        lastSelectedAccountState.value = ContactAccount("", "")
    }
}

