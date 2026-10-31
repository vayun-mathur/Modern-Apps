package com.vayunmathur.contacts.util

import android.app.Application
import android.content.ContentProviderOperation
import android.provider.ContactsContract
import com.vayunmathur.contacts.data.rewriteNoYearSentinel
import com.vayunmathur.library.log.Log
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import kotlinx.coroutines.flow.first

/**
 * One-time DataStore migrations for account keys, kept out of
 * ContactViewModelAccounts so each file stays under the function cap
 * (see TooManyFunctions). Called as viewModel.*.
 */

internal suspend fun ContactViewModel.migrateLegacyAccounts() {
    val legacyAccounts = dataStore.getString("extra_accounts").orEmpty()
    if (legacyAccounts.isBlank()) return
    legacyAccounts.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { legacyEntry -> migrateLegacyAccountEntry(legacyEntry) }
    dataStore.setString("extra_accounts", "")
}

private suspend fun ContactViewModel.migrateLegacyAccountEntry(legacyEntry: String) {
    val clean = decodeExtraAccountEntryClean(legacyEntry)
    if (clean != null) {
        dataStore.addStringToSetIfAbsent("extra_accounts_set", legacyEntry)
        return
    }
    if (!legacyEntry.contains("|")) {
        val encoded = encodeExtraAccountEntry(legacyEntry, LOCAL_ACCOUNT_TYPE)
        dataStore.addStringToSetIfAbsent("extra_accounts_set", encoded)
        return
    }
    val nameOld = legacyEntry.substringBeforeLast("|")
    val typeOld = legacyEntry.substringAfterLast("|")
    if (nameOld.isNotEmpty()) {
        val encoded = encodeExtraAccountEntry(nameOld, typeOld)
        dataStore.addStringToSetIfAbsent("extra_accounts_set", encoded)
    }
}

internal suspend fun ContactViewModel.loadSavedAccounts(): List<ContactAccount> {
    val savedSet = dataStore.stringSetFlow("extra_accounts_set").first()
    val savedAccounts = mutableListOf<ContactAccount>()
    for (entry in savedSet) {
        migrateSavedAccountEntry(entry, savedAccounts)
    }
    return savedAccounts
}

/** Normalizes one saved-accounts entry; single exit so the loop has one jump. */
private suspend fun ContactViewModel.migrateSavedAccountEntry(
    entry: String,
    savedAccounts: MutableList<ContactAccount>,
) {
    val account = decodeSavedAccountEntry(entry)
    if (account == null) return
    savedAccounts.add(account)
    if (isCleanSavedEntry(entry)) return
    dataStore.removeStringFromSet("extra_accounts_set", entry)
    val encoded = encodeExtraAccountEntry(account.name, account.type)
    dataStore.addStringToSetIfAbsent("extra_accounts_set", encoded)
}

private fun decodeSavedAccountEntry(entry: String): ContactAccount? {
    decodeExtraAccountEntryClean(entry)?.let { return it }
    if (entry.contains("|")) {
        val nameOld = entry.substringBeforeLast("|")
        val typeOld = entry.substringAfterLast("|")
        if (nameOld.isEmpty()) return null
        return ContactAccount(nameOld, typeOld)
    }
    if (entry.isEmpty()) return null
    val decodedName = decodeAccountComponent(entry)
    val account = ContactAccount(decodedName.ifEmpty { entry }, LOCAL_ACCOUNT_TYPE)
    return account.takeIf { it.name.isNotEmpty() }
}

private fun isCleanSavedEntry(entry: String): Boolean =
    decodeExtraAccountEntryClean(entry) != null

// Migrate hidden_accounts keys containing raw separators to encoded form.
// Broad catch is deliberate: DataStore reads fail with OEM-specific
// RuntimeExceptions and a best-effort migration must not break account load.
@Suppress("TooGenericExceptionCaught")
internal suspend fun ContactViewModel.migrateHiddenAccountKeys() {
    try {
        val hiddenSet = dataStore.getStringSetAwait("hidden_accounts")
        for (hiddenEntry in hiddenSet) {
            migrateHiddenAccountKey(hiddenEntry)
        }
    } catch (e: Exception) {
        Log.status("ContactViewModel", "Error migrating hidden accounts", e)
    }
}

/** Migrates one hidden-accounts key; single exit so the loop has one jump. */
private suspend fun ContactViewModel.migrateHiddenAccountKey(hiddenEntry: String) {
    if (shouldKeepHiddenKey(hiddenEntry)) return
    val typeOld = hiddenEntry.substringBefore("|")
    val nameOld = hiddenEntry.substringAfter("|")
    if (typeOld.isEmpty() && nameOld.isEmpty()) return
    dataStore.removeStringFromSet("hidden_accounts", hiddenEntry)
    dataStore.addStringToSet("hidden_accounts", encodeHiddenAccountKey(typeOld, nameOld))
}

private fun shouldKeepHiddenKey(hiddenEntry: String): Boolean {
    if (!hiddenEntry.contains("|")) return true
    return decodeHiddenKeyClean(hiddenEntry) != null
}

/**
 * One-time rewrite of Event rows stored with the internal "1604-MM-DD"
 * no-year sentinel to the "--MM-DD" provider form, so year-less birthdays
 * saved before the write-path fix become visible to Google Contacts and
 * third-party readers (e.g. KashCal). Guarded by a DataStore flag so it
 * runs once; rows already carrying a real year or "--MM-DD" are untouched.
 *
 * Broad catch is deliberate: provider writes fail with OEM-specific
 * RuntimeExceptions and a best-effort migration must not break contact load.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ContactViewModel.migrateNoYearEventDates() {
    try {
        if (dataStore.getBoolean("no_year_event_dates_migrated", false)) return
        migrateNoYearEventDatesInternal()
        dataStore.setBoolean("no_year_event_dates_migrated", true)
    } catch (e: Exception) {
        Log.status("ContactViewModel", "Error migrating no-year event dates", e)
    }
}

private suspend fun ContactViewModel.migrateNoYearEventDatesInternal() {
    val app = getApplication<Application>()
    val ops = ArrayList<ContentProviderOperation>()
    app.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
        "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} LIKE ?",
        arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            "1604-%",
        ),
        null,
    )?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
        val dateIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
        while (cursor.moveToNext()) {
            val rewritten = rewriteNoYearSentinel(cursor.getString(dateIdx) ?: "")
                ?: continue
            ops += ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(cursor.getLong(idIdx).toString()))
                .withValue(ContactsContract.Data.DATA1, rewritten)
                .build()
        }
    }
    if (ops.isEmpty()) return
    app.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
}

/**
 * One-time rewrite of the "Show birthdays in calendar" calendar from
 * local-midnight rows to UTC-midnight all-day rows (see issue #859):
 * birthdays synced before the writer fix render a day early while DST is
 * in effect. [CalendarSyncHelper.syncAll] already wipes and re-inserts the
 * whole calendar, so running it once repairs every stale row; the flag
 * keeps it to a single launch. Skipped (flag unset) while birthday sync
 * is off — enabling sync runs the same full rewrite through the fixed
 * writer anyway.
 *
 * Broad catch is deliberate: provider writes fail with OEM-specific
 * RuntimeExceptions and a best-effort migration must not break contact load.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ContactViewModel.migrateBirthdayCalendarToUtc() {
    try {
        if (dataStore.getBoolean("birthday_calendar_utc_migrated", false)) return
        if (!dataStore.getBoolean("calendar_sync_enabled", false)) return
        CalendarSyncHelper.syncAll(getApplication<Application>())
        dataStore.setBoolean("birthday_calendar_utc_migrated", true)
    } catch (e: Exception) {
        Log.status("ContactViewModel", "Error migrating birthday calendar", e)
    }
}

