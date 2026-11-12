package com.vayunmathur.contacts.util

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.log.Log
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SaveOutcome
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SIM-backed save path and SIM account labels, kept out of ContactViewModel
 * so the class stays under the function cap (see TooManyFunctions).
 * Called as viewModel.*.
 */
internal suspend fun ContactViewModel.persistSimContact(
    contact: com.vayunmathur.contacts.data.Contact
): Boolean {
    val subId = contact.accountName?.toIntOrNull()
    val name = simPersistName(contact)
    val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
    val email = contact.details.emails.firstOrNull()?.address?.trim()?.takeIf { it.isNotEmpty() }
    if (name.isBlank() && number.isBlank()) {
        Log.status("ContactViewModel", "SIM save skipped: name and number empty")
        return false
    }
    if (contact.id < 0) {
        updateExistingSimContact(contact, name, number, email, subId)?.let { return it }
    }
    val ok = SimContactsDataSource.insertSimContact(getApplication(), name, number, email, subId)
    if (!ok) Log.error("ContactViewModel", "Failed to insert SIM contact")
    syncFromSystem()
    if (ok) scheduleSimConvergenceSync()
    return ok
}

/**
 * Id of the SIM contact just written from [contact], read from the post-save sync.
 * SIM ids hash the row's contents ([SimContactsDataSource.syntheticIdFor]), so every edit
 * changes them. Falls back to number-only, then name-only, since cards may truncate names.
 */
internal fun ContactViewModel.findSavedSimContactId(
    contact: com.vayunmathur.contacts.data.Contact
): Long? {
    val subId = contact.accountName?.toIntOrNull()?.toString()
    val name = simPersistName(contact)
    val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim().orEmpty()
    val candidates = allContactsState.value.filter {
        isSimAccountType(it.accountType) && (subId == null || it.accountName == subId)
    }
    fun numberOf(c: com.vayunmathur.contacts.data.Contact) =
        c.details.phoneNumbers.firstOrNull()?.number?.trim().orEmpty()
    val match = candidates.firstOrNull { it.name.value.trim() == name && numberOf(it) == number }
        ?: candidates.firstOrNull { number.isNotEmpty() && numberOf(it) == number }
        ?: candidates.firstOrNull { name.isNotEmpty() && it.name.value.trim() == name }
    return match?.id
}

internal fun ContactViewModel.simPersistName(
    contact: com.vayunmathur.contacts.data.Contact
): String =
    contact.name.value.trim().ifEmpty {
        contact.nickname.nickname.trim().ifEmpty {
            contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
        }
    }

/**
 * Updates the existing SIM row in place (or relocates it). Returns null when there is
 * no existing row and the caller should fall through to insert.
 */
internal suspend fun ContactViewModel.updateExistingSimContact(
    contact: com.vayunmathur.contacts.data.Contact,
    name: String,
    number: String,
    email: String?,
    subId: Int?,
): Boolean? {
    val app = getApplication<Application>()
    val oldSc = SimContactsDataSource.listSimContacts(app)
        .firstOrNull { SimContactsDataSource.syntheticIdFor(it) == contact.id }
        ?: SimContactsDataSource.findBackingSimContact(app, contact)
        ?: return null
    // Skip if no actual change
    val isUnchanged = oldSc.name == name &&
        oldSc.number == number &&
        oldSc.emails == email &&
        oldSc.subscriptionId == subId
    if (isUnchanged) return true
    // Edit in place where possible. Moving a contact to a different SIM is not an
    // in-place edit, so that still falls through to delete-then-insert.
    val canEditInPlace = subId == null || subId == oldSc.subscriptionId
    if (canEditInPlace) {
        val updated = SimContactsDataSource.updateSimContact(app, oldSc, name, number, email, subId)
        if (updated) {
            syncFromSystem()
            scheduleSimConvergenceSync()
            return true
        }
    }
    SimContactsDataSource.deleteSimContact(app, oldSc)
    return null
}

/**
 * Re-reads the SIM list once the ADN provider's read cache has had time to
 * converge on our write. Intermittently the immediate post-write
 * [syncFromSystem] reads stale provider data (the contact then appears only
 * after a SIM reinsert, see #841): the provider serves writes from a cache
 * that converges asynchronously, and nothing re-reads afterwards. The delayed
 * re-sync is coalesced through the debounced sync path, so bursts of SIM
 * writes cause at most one extra read.
 */
internal fun ContactViewModel.scheduleSimConvergenceSync() {
    viewModelScope.launch {
        delay(SIM_CONVERGENCE_DELAY_MILLIS)
        syncFromSystem()
    }
}

/** Delay before the SIM convergence re-sync; hoisted so tests can reference it. */
internal const val SIM_CONVERGENCE_DELAY_MILLIS = 2_000L

/**
 * Writes [contact] to the SIM or the contacts provider, telling a provider refusal of
 * the target account (#916) apart from other failures.
 */
internal suspend fun ContactViewModel.persistContactWithOutcome(
    contact: com.vayunmathur.contacts.data.Contact
): SaveOutcome {
    if (isSimAccountType(contact.accountType)) {
        return if (persistSimContact(contact)) SaveOutcome.SAVED else SaveOutcome.FAILED
    }
    return persistProviderContact(contact)
}

internal suspend fun ContactViewModel.persistProviderContact(
    contact: com.vayunmathur.contacts.data.Contact
): SaveOutcome {
    val app = getApplication<Application>()
    val contactId = contact.id
    val details = contact.details
    // Read the old rows straight from the provider rather than trusting the cached
    // list: the cache lags the provider across account syncs and external edits, and
    // updates built against stale data-row ids match zero rows without error, so the
    // batch reports success while nothing changes.
    val oldDetails = contactId.takeIf { it != 0L }?.let {
        runCatching { com.vayunmathur.contacts.data.getDetails(app, it) }.getOrNull()
            ?: com.vayunmathur.contacts.data.ContactDetails.empty()
    } ?: contacts.value.find { it.id == contactId }?.details
        ?: allContactsState.value.find { it.id == contactId }?.details
        ?: com.vayunmathur.contacts.data.ContactDetails.empty()
    val outcome = contact.saveWithOutcome(app, details, oldDetails)
    if (outcome == SaveOutcome.SAVED) {
        // The provider observer re-syncs on its own debounce, but an edit must be
        // visible immediately - refresh now so the details page and list update
        // without waiting for the observer round-trip.
        syncFromSystem()
    }
    return outcome
}

fun ContactViewModel.simDisplayLabel(account: ContactAccount): String? =
    simAccountLabelsState.value["${account.type}|${account.name}"]

fun ContactViewModel.simDisplayLabelFor(type: String?, name: String?): String? =
    simAccountLabelsState.value["${type ?: ""}|${name ?: ""}"]

internal fun ContactViewModel.simSlotLabelsFor(
    infos: List<SimContactsDataSource.SimSubscriptionInfo>
): Map<String, String> {
    val app = getApplication<Application>()
    return infos.associate { info ->
        "$SIM_ACCOUNT_TYPE|${SimContactsDataSource.accountNameFor(info)}" to
            app.getString(R.string.sim_slot_label, SimContactsDataSource.slotNumberFor(info))
    }
}
