@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.vayunmathur.contacts.util

import android.app.Application
import android.graphics.Bitmap
import com.vayunmathur.library.log.Log
import androidx.core.graphics.scale
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
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.data.SaveOutcome
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

fun ContactViewModel.initEditDraft(
    contactId: Long?,
    prefill: ContactPrefill? = null,
) {
    if (editingInitialized && editingContactId == contactId && editDraftState.value != null) return
    val contact = contactId?.let { lookupDraftContact(it) }
    val details = contact?.details
    editingOriginal = contact
    editingContactId = contactId
    editingInitialized = true
    editDraftState.value = buildDraft(contact, details, prefill)
}

private fun ContactViewModel.lookupDraftContact(contactId: Long): Contact? =
    getContact(contactId)
        ?: Contact.getContact(getApplication<Application>(), contactId)
        ?: allContactsState.value.find { c -> c.id == contactId }

private fun ContactViewModel.buildDraft(
    contact: Contact?,
    details: ContactDetails?,
    prefill: ContactPrefill?,
): ContactViewModel.ContactDraft =
    withMetaFields(draftWithNames(contact, prefill), contact, prefill)
        .withListFields(this, details, prefill)

private fun draftWithNames(
    contact: Contact?,
    prefill: ContactPrefill?,
): ContactViewModel.ContactDraft = ContactViewModel.ContactDraft(
    namePrefix = contact?.name?.namePrefix ?: "",
    firstName = contact?.name?.firstName ?: prefill?.name ?: "",
    middleName = contact?.name?.middleName ?: "",
    lastName = contact?.name?.lastName ?: "",
    nameSuffix = contact?.name?.nameSuffix ?: "",
)

private fun ContactViewModel.withMetaFields(
    base: ContactViewModel.ContactDraft,
    contact: Contact?,
    prefill: ContactPrefill?,
): ContactViewModel.ContactDraft = base.copy(
    company = contact?.org?.company ?: prefill?.company ?: "",
    noteContent = contact?.note?.content ?: prefill?.notes ?: "",
    nickname = contact?.nickname?.nickname ?: prefill?.nickname ?: "",
    genderCode = contact?.details?.genders?.firstOrNull()?.code ?: "",
    genderText = contact?.details?.genders?.firstOrNull()?.text ?: "",
    photo = contact?.photo,
    birthday = contact?.birthday?.startDate,
    accountName = draftAccountName(contact),
    accountType = draftAccountType(contact),
)

private fun ContactViewModel.ContactDraft.withListFields(
    viewModel: ContactViewModel,
    details: ContactDetails?,
    prefill: ContactPrefill?,
): ContactViewModel.ContactDraft = copy(
    // Almost every contact has a phone number, so the editor opens with a row ready
    // rather than making the user press "add phone" first. Blank rows are dropped on save.
    phoneNumbers = viewModel.draftPhoneNumbers(details, prefill),
    emails = viewModel.mergeEmails(details?.emails ?: emptyList(), prefill?.emails ?: emptyList()),
    dates = details?.dates ?: emptyList(),
    addresses = viewModel.mergeAddresses(
        details?.addresses ?: emptyList(),
        prefill?.postals ?: emptyList()
    ),
    groupMemberships = details?.groups ?: emptyList(),
    commPref = details?.commPref,
)

// An existing contact keeps its own account even when that is on-device (null); only a new
// contact falls back to the last account picked in the add screen, or to the cloud default
// when the provider would refuse that account (#916).
private fun ContactViewModel.draftAccountName(contact: Contact?): String =
    if (contact != null) contact.accountName.orEmpty() else newContactDefaultAccount().name

private fun ContactViewModel.draftAccountType(contact: Contact?): String =
    if (contact != null) contact.accountType.orEmpty() else newContactDefaultAccount().type

private fun ContactViewModel.draftPhoneNumbers(
    details: ContactDetails?,
    prefill: ContactPrefill?,
): List<PhoneNumber> =
    mergePhones(details?.phoneNumbers ?: emptyList(), prefill?.phones ?: emptyList())
        .ifEmpty { listOf(ContactDetail.default<PhoneNumber>()) }

/** Appends prefilled phones that aren't already present (compared by normalized number). */
internal fun ContactViewModel.mergePhones(
    existing: List<PhoneNumber>,
    prefill: List<PrefillValue>
): List<PhoneNumber> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    val known = result.mapTo(mutableSetOf()) { normalizePhoneForCompare(it.number) }
    for (p in prefill) {
        val norm = normalizePhoneForCompare(p.value)
        if (norm.isEmpty() || !known.add(norm)) continue
        result += PhoneNumber(0, p.value, p.type ?: CDKPhone.TYPE_MOBILE, p.label ?: "")
    }
    return result
}

/** Appends prefilled emails that aren't already present (case-insensitive address match). */
internal fun ContactViewModel.mergeEmails(
    existing: List<Email>,
    prefill: List<PrefillValue>
): List<Email> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    val known = result.mapTo(mutableSetOf()) { it.address.trim().lowercase() }
    for (e in prefill) {
        val v = e.value.trim()
        if (v.isEmpty() || !known.add(v.lowercase())) continue
        result += Email(0, e.value, e.type ?: CDKEmail.TYPE_HOME, e.label ?: "")
    }
    return result
}

/** Appends prefilled postal addresses that aren't already present (case-insensitive match). */
internal fun ContactViewModel.mergeAddresses(
    existing: List<Address>,
    prefill: List<PrefillValue>
): List<Address> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    val known = result.mapTo(mutableSetOf()) { it.formattedAddress.trim().lowercase() }
    for (a in prefill) {
        val v = a.value.trim()
        if (v.isEmpty() || !known.add(v.lowercase())) continue
        result += Address(0, a.value, a.type ?: CDKStructuredPostal.TYPE_HOME, a.label ?: "")
    }
    return result
}

/**
 * Used by INSERT_OR_EDIT / SHOW_OR_CREATE_CONTACT flow when the user picks an existing
 * contact from the dialer. Directly appends [phone] (if not already present) and saves,
 * without opening the full editor.
 */
fun ContactViewModel.addPhoneNumberToContact(
    contactId: Long,
    phone: String,
    type: Int = CDKPhone.TYPE_MOBILE,
    onComplete: () -> Unit = {}
) {
    viewModelScope.launch(Dispatchers.IO) {
        // Broad catch is deliberate: the dialer flow crosses provider, SIM ADN
        // and DataStore writes, and must always invoke onComplete.
        @Suppress("TooGenericExceptionCaught")
        try {
            appendPhoneNumber(contactId, phone, type)
        } catch (e: Exception) {
            Log.error("ContactViewModel", "Failed to add phone to contact $contactId", e)
        }
        withContext(Dispatchers.Main) { onComplete() }
    }
}

private suspend fun ContactViewModel.appendPhoneNumber(
    contactId: Long,
    phone: String,
    type: Int,
) {
    val app = getApplication<Application>()
    val existing = Contact.getContact(app, contactId)
        ?: allContactsState.value.find { it.id == contactId }
        ?: contacts.value.find { it.id == contactId }
        ?: return
    if (isSimAccountType(existing.accountType)) {
        appendSimPhoneNumber(app, existing, phone)
    } else {
        appendProviderPhoneNumber(app, existing, phone, type)
    }
}

private suspend fun ContactViewModel.appendSimPhoneNumber(
    app: Application,
    existing: Contact,
    phone: String,
) {
    val subId = existing.accountName?.toIntOrNull()
    if (hasPhoneNumber(existing, phone)) return
    val name = existing.name.value
    if (existing.details.phoneNumbers.isEmpty()) {
        val oldSc = SimContactsDataSource.findBackingSimContact(app, existing)
        if (oldSc != null) SimContactsDataSource.deleteSimContact(app, oldSc)
    }
    // SIM can hold only one number; store the new number as an additional SIM entry
    SimContactsDataSource.insertSimContact(app, name.ifEmpty { phone }, phone, null, subId)
    syncFromSystem()
}

private fun ContactViewModel.appendProviderPhoneNumber(
    app: Application,
    existing: Contact,
    phone: String,
    type: Int,
) {
    if (hasPhoneNumber(existing, phone)) return
    val newDetails = existing.details.copy(
        phoneNumbers = existing.details.phoneNumbers + PhoneNumber(0, phone, type)
    )
    existing.save(app, newDetails, existing.details)
}

private fun ContactViewModel.hasPhoneNumber(existing: Contact, phone: String): Boolean {
    val normalizedNew = normalizePhoneForCompare(phone)
    return existing.details.phoneNumbers.any {
        normalizePhoneForCompare(it.number) == normalizedNew
    }
}

/** Applies [transform] to the current draft, if any. */
fun ContactViewModel.updateEditDraft(
    transform: (ContactViewModel.ContactDraft) -> ContactViewModel.ContactDraft
) {
    val current = editDraftState.value ?: return
    editDraftState.value = transform(current)
}

/** Clears the in-progress draft and forgets which contact was being edited. */
fun ContactViewModel.clearEditDraft() {
    editDraftState.value = null
    editingOriginal = null
    editingContactId = null
    editingInitialized = false
}

/**
 * Decodes the picked image URI off the main thread, scales it to 500x500,
 * Base64-encodes it, and updates [editDraft]'s photo.
 */
fun ContactViewModel.setEditDraftPhotoFromBitmap(bitmap: Bitmap) {
    viewModelScope.launch(Dispatchers.IO) {
        val scaled = if (bitmap.width != PHOTO_SIZE || bitmap.height != PHOTO_SIZE) {
            bitmap.scale(PHOTO_SIZE, PHOTO_SIZE)
        } else bitmap
        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, baos)
        val encoded = Base64.encode(baos.toByteArray())
        updateEditDraft { draft ->
            val newPhoto = draft.photo?.withValue(encoded)
                ?: com.vayunmathur.contacts.data.Photo(0, encoded)
            draft.copy(photo = newPhoto)
        }
    }
}

private const val PHOTO_SIZE = 1024
private const val PHOTO_JPEG_QUALITY = 100

/**
 * Persists the current draft via the unified save path.
 * SIM vs device routing is handled by [saveContact] based on draft.accountType.
 * [onResult] receives the saved contact's id, which differs from the edited one for SIM rows.
 */
fun ContactViewModel.saveEditDraft(onResult: ((Boolean, String?, Long?) -> Unit)? = null) {
    val draft = editDraftState.value ?: return
    // For SIM accounts, validate SIM limits: at least name or phone needed, and SIM can't store extra fields
    if (isSimAccountType(draft.accountType) && !hasSimNameOrPhone(draft)) {
        val message = getApplication<Application>().getString(R.string.sim_name_or_phone_required)
        onResult?.invoke(false, message, null)
        return
    }
    val newContact = buildContactFromDraft(draft)
    viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) { persistContactWithOutcome(newContact) }
        val ok = outcome == SaveOutcome.SAVED
        val savedId = when {
            !ok -> null
            isSimAccountType(newContact.accountType) -> findSavedSimContactId(newContact)
            else -> newContact.id.takeIf { it != 0L }
        }
        if (ok) clearEditDraft()
        // A refused account gets its reason (#916); other failures keep the caller's generic text.
        val error = if (outcome == SaveOutcome.REJECTED_CLOUD_DEFAULT) cloudDefaultRejectionMessage() else null
        onResult?.invoke(ok, error, savedId)
    }
}

private fun hasSimNameOrPhone(draft: ContactViewModel.ContactDraft): Boolean {
    val nameVal = listOfNotNull(
        draft.namePrefix.ifEmpty { null },
        draft.firstName.ifEmpty { null },
        draft.middleName.ifEmpty { null },
        draft.lastName.ifEmpty { null },
        draft.nameSuffix.ifEmpty { null }
    ).joinToString(" ").trim()
    val phoneVal = draft.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
    return nameVal.isNotEmpty() || phoneVal.isNotEmpty()
}

private fun ContactViewModel.buildContactFromDraft(
    draft: ContactViewModel.ContactDraft
): Contact {
    val original = editingOriginal
    val details = buildDetailsFromDraft(draft, original)
    // For existing SIM contacts, keep synthetic id so saveContact can locate old row.
    // The provider requires an account to name both fields or neither, so a draft pointing at
    // a half-renamed account saves to device-local rather than being rejected.
    val accountType = draft.accountType.ifEmpty { null }
    val accountName = if (accountType == null) null else draft.accountName.ifEmpty { null }
    return original?.copy(
        accountType = accountType,
        accountName = accountName,
        details = details
    ) ?: Contact(
        id = 0,
        accountType = accountType,
        accountName = accountName,
        isFavorite = false,
        details = details
    )
}

private fun buildDetailsFromDraft(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): ContactDetails {
    val phoneNumbers = draft.phoneNumbers.filter { it.number.isNotBlank() }
    val emails = draft.emails.filter { it.address.isNotBlank() }
    val addresses = draft.addresses.filter { it.formattedAddress.isNotBlank() }
    val datesWithoutBirthday = mergeDraftBirthday(draft, original)
    // Blank org/note/nickname placeholder rows are dropped so they aren't persisted;
    // a cleared field yields an empty list so the old row is deleted via id diff.
    val orgs = draftOrg(draft, original)
    val notes = draftNote(draft, original)
    val nicknames = draftNickname(draft, original)
    val genders = draftGender(draft, original)
    val commPrefs = draftCommPref(draft, original)
    // SIM cards can store only name parts, a first phone, and at most a first email.
    // Defensively strip everything else so SIM saves never persist unsupported rows.
    val isSimDraft = isSimAccountType(draft.accountType)
    return ContactDetails(
        phoneNumbers = if (isSimDraft) listOfNotNull(phoneNumbers.firstOrNull()) else phoneNumbers,
        emails = if (isSimDraft) listOfNotNull(emails.firstOrNull()) else emails,
        addresses = if (isSimDraft) emptyList() else addresses,
        dates = if (isSimDraft) emptyList() else datesWithoutBirthday,
        photos = if (isSimDraft) emptyList() else listOfNotNull(draft.photo),
        names = listOf(draftName(draft, original)),
        orgs = if (isSimDraft) emptyList() else orgs,
        notes = if (isSimDraft) emptyList() else notes,
        nicknames = if (isSimDraft) emptyList() else nicknames,
        groups = draft.groupMemberships,
        genders = if (isSimDraft) emptyList() else genders,
        // SIM ADN rows store only name/phone/email and have no Data rows.
        commPrefs = if (isSimDraft) emptyList() else commPrefs,
    )
}

private fun mergeDraftBirthday(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): List<Event> {
    val birthdayId = original?.birthday?.id ?: 0L
    val datesWithoutBirthday =
        draft.dates.filter { it.type != CDKEvent.TYPE_BIRTHDAY }.toMutableList()
    draft.birthday?.let { bday ->
        datesWithoutBirthday += Event(birthdayId, bday, CDKEvent.TYPE_BIRTHDAY)
    }
    return datesWithoutBirthday
}

private fun draftOrg(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): List<Organization> {
    if (draft.company.isBlank()) return emptyList()
    return listOf(Organization(original?.details?.orgs?.firstOrNull()?.id ?: 0, draft.company))
}

private fun draftNote(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): List<Note> {
    if (draft.noteContent.isBlank()) return emptyList()
    return listOf(Note(original?.details?.notes?.firstOrNull()?.id ?: 0, draft.noteContent))
}

private fun draftNickname(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): List<Nickname> {
    if (draft.nickname.isBlank()) return emptyList()
    val id = original?.details?.nicknames
        ?.firstOrNull { it.type == CDKNickname.TYPE_DEFAULT }?.id ?: 0
    return listOf(Nickname(id, draft.nickname, CDKNickname.TYPE_DEFAULT))
}

private fun draftGender(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): List<Gender> {
    if (draft.genderCode.isBlank()) return emptyList()
    val id = original?.details?.genders?.firstOrNull()?.id ?: 0
    return listOf(Gender(id, draft.genderCode, draft.genderText))
}

/** Keeps the existing pref row id so an edit updates rather than duplicates. */
private fun draftCommPref(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): List<CommPref> {
    val pref = draft.commPref ?: return emptyList()
    if (pref.number.isBlank()) return emptyList()
    val id = original?.details?.commPrefs?.firstOrNull()?.id ?: 0
    return listOf(pref.copy(id = id))
}

private fun draftName(
    draft: ContactViewModel.ContactDraft,
    original: Contact?,
): Name = Name(
    original?.name?.id ?: 0,
    draft.namePrefix,
    draft.firstName,
    draft.middleName,
    draft.lastName,
    draft.nameSuffix
)

