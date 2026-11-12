package com.vayunmathur.contacts.data
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.RINGTONE_SILENT
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import java.util.Locale

val LocalDate.hasYear: Boolean get() = year >= 1901

/**
 * Field order, month name and punctuation all follow [locale] - the same source the date picker
 * uses - so the detail view and the picker cannot disagree.
 */
fun LocalDate.formatDisplay(locale: Locale = Locale.getDefault()): String =
    DateString.dateWithOptionalYear(this, hasYear, locale)


@Serializable
data class ContactDetails(
    val phoneNumbers: List<PhoneNumber>,
    val emails: List<Email>,
    val addresses: List<Address>,
    val dates: List<Event>,
    val photos: List<Photo>,
    val names: List<Name>,
    val orgs: List<Organization>,
    val notes: List<Note>,
    val nicknames: List<Nickname>,
    val groups: List<GroupMembership>,
    val genders: List<Gender> = emptyList(),
    val commPrefs: List<CommPref> = emptyList(),
) {
    fun all(): List<ContactDetail<*>> {
        return phoneNumbers + emails + addresses + dates + photos + names + orgs + notes + nicknames + groups + genders + commPrefs
    }

    /** Single shared preferred number + platform; null means ask every time. */
    val commPref: CommPref?
        get() = commPrefs.firstOrNull()

    companion object {
        fun empty() = ContactDetails(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )
    }
}

typealias CDKEmail = ContactsContract.CommonDataKinds.Email
typealias CDKPhone = ContactsContract.CommonDataKinds.Phone
typealias CDKStructuredPostal = ContactsContract.CommonDataKinds.StructuredPostal
typealias CDKEvent = ContactsContract.CommonDataKinds.Event
typealias CDKPhoto = ContactsContract.CommonDataKinds.Photo
typealias CDKSName = ContactsContract.CommonDataKinds.StructuredName
typealias CDKOrg = ContactsContract.CommonDataKinds.Organization
typealias CDKNote = ContactsContract.CommonDataKinds.Note
typealias CDKNickname = ContactsContract.CommonDataKinds.Nickname
typealias CDKGroupMembership = ContactsContract.CommonDataKinds.GroupMembership

interface ContactDetail<T: ContactDetail<T>> {
    val id: Long
    val type: Int
    val value: String
    fun withType(type: Int): T
    fun withValue(value: String): T
    fun withLabel(label: String): T
    fun typeString(context: Context): String

    companion object {
        @OptIn(ExperimentalTime::class)
        inline fun <reified T: ContactDetail<T>> default(): T {
            return when (T::class) {
                PhoneNumber::class -> PhoneNumber(0, "", CDKPhone.TYPE_MOBILE)
                Email::class -> Email(0, "", CDKEmail.TYPE_HOME)
                Address::class -> Address(0, "", CDKStructuredPostal.TYPE_HOME)
                Event::class -> Event(
                    0,
                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
                    CDKEvent.TYPE_OTHER
                )
                else -> throw IllegalArgumentException("Unknown type")
            } as T
        }
    }
}

@Serializable
data class PhoneNumber(
    override val id: Long,
    val number: String,
    override val type: Int,
    val label: String = ""
) : ContactDetail<PhoneNumber> {
    override val value: String
        get() = number

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(number = value)
    override fun withLabel(label: String) = copy(label = label)

    override fun typeString(context: Context) = CDKPhone.getTypeLabel(context.resources, type, label).toString()
}

@Serializable
data class Email(
    override val id: Long,
    val address: String,
    override val type: Int,
    val label: String = ""
) : ContactDetail<Email> {
    override val value: String
        get() = address

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(address = value)
    override fun withLabel(label: String) = copy(label = label)

    override fun typeString(context: Context) = CDKEmail.getTypeLabel(context.resources, type, label).toString()
}

@Serializable
data class Address(
    override val id: Long,
    val formattedAddress: String,
    override val type: Int,
    val label: String = ""
) : ContactDetail<Address> {
    override val value: String
        get() = formattedAddress

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(formattedAddress = value)
    override fun withLabel(label: String) = copy(label = label)

    override fun typeString(context: Context) =
        CDKStructuredPostal.getTypeLabel(context.resources, type, label).toString()
}

@Serializable
data class Photo(override val id: Long, val photo: String): ContactDetail<Photo> {
    override val type: Int = 0
    override val value: String
        get() = photo

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of photo")
    override fun withValue(value: String) = Photo(id, value)
    override fun withLabel(label: String): Photo = this

    override fun typeString(context: Context) = throw UnsupportedOperationException("Photo doesn't have type")
}

@Serializable
data class Event(
    override val id: Long,
    val startDate: LocalDate,
    override val type: Int,
    val label: String = ""
) : ContactDetail<Event> {
    override val value: String
        get() = startDate.format(LocalDate.Formats.ISO)

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(startDate = LocalDate.parse(value))
    override fun withLabel(label: String) = copy(label = label)

    override fun typeString(context: Context) = CDKEvent.getTypeLabel(context.resources, type, label).toString()
}

@Serializable
data class Organization(override val id: Long, val company: String): ContactDetail<Organization> {
    override val type: Int = 0
    override val value: String
        get() = company

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of photo")
    override fun withValue(value: String) = Organization(id, value)
    override fun withLabel(label: String): Organization = this

    override fun typeString(context: Context) = throw UnsupportedOperationException("Photo doesn't have type")
}

@Serializable
data class Name(
    override val id: Long,
    val namePrefix: String,
    val firstName: String,
    val middleName: String,
    val lastName: String,
    val nameSuffix: String
): ContactDetail<Name> {
    override val type: Int = 0
    override val value: String
        get() = listOfNotNull(
            namePrefix.ifEmpty { null },
            firstName.ifEmpty { null },
            middleName.ifEmpty { null },
            lastName.ifEmpty { null },
            nameSuffix.ifEmpty { null }
        ).joinToString(" ")

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of name")
    override fun withValue(value: String) = throw UnsupportedOperationException("Cannot change value of name")
    override fun withLabel(label: String): Name = this

    override fun typeString(context: Context) = throw UnsupportedOperationException("Name doesn't have type")
}

@Serializable
data class Note(override val id: Long, val content: String): ContactDetail<Note> {
    override val type: Int = 0
    override val value: String
        get() = content

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of note")
    override fun withValue(value: String) = copy(content = value)
    override fun withLabel(label: String): Note = this

    override fun typeString(context: Context) = throw UnsupportedOperationException("Note doesn't have type")
}

@Serializable
data class Nickname(override val id: Long, val nickname: String, override val type: Int): ContactDetail<Nickname> {
    override val value: String
        get() = nickname

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(nickname = value)
    override fun withLabel(label: String): Nickname = this

    override fun typeString(context: Context) =
        throw UnsupportedOperationException("Nickname types shouldn't be written")
}

@Serializable
data class GroupMembership(override val id: Long, val groupId: Long): ContactDetail<GroupMembership> {
    override val type: Int = 0
    override val value: String
        get() = groupId.toString()

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of group membership")
    override fun withValue(value: String) = copy(groupId = value.toLong())
    override fun withLabel(label: String): GroupMembership = this

    override fun typeString(context: Context) =
        throw UnsupportedOperationException("Group membership doesn't have type")
}

/**
 * Singleton gender detail (RFC 6350 §6.2.7 sex/gender value): [code] is one of
 * F/M/O/U, [text] carries the free-form value for O (and is otherwise blank).
 * Persisted under a custom MIME type in DATA1 (code) + DATA2 (text); never stored
 * on SIM contacts.
 */
@Serializable
data class Gender(
    override val id: Long,
    val code: String,
    val text: String = "",
) : ContactDetail<Gender> {
    override val type: Int = 0
    override val value: String
        get() = if (code == OTHER && text.isNotBlank()) "$code;$text" else code

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of gender")
    override fun withValue(value: String): Gender {
        val parts = value.split(";")
        return copy(code = parts.getOrNull(0).orEmpty(), text = parts.drop(1).joinToString(";"))
    }
    override fun withLabel(label: String): Gender = this

    override fun typeString(context: Context) =
        throw UnsupportedOperationException("Gender doesn't have type")

    companion object {
        const val FEMALE = "F"
        const val MALE = "M"
        const val OTHER = "O"
        const val UNSPECIFIED = "U"
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.com.vayunmathur.contacts.gender"

        /** Canonical code for a raw string, or null when unrecognized. */
        fun canonicalCode(raw: String): String? = when (raw.trim().uppercase()) {
            FEMALE, "FEMALE", "W", "WOMAN", "GIRL" -> FEMALE
            MALE, "MALE", "MAN", "BOY" -> MALE
            OTHER, "OTHER", "X", "DIVERSE", "NONBINARY", "NON-BINARY" -> OTHER
            UNSPECIFIED, "UNSPECIFIED", "UNKNOWN", "" -> UNSPECIFIED
            else -> null
        }
    }
}

@Serializable
data class Contact(
    val id: Long,
    val accountType: String?,
    val accountName: String?,
    val isFavorite: Boolean,
    val details: ContactDetails,
    /** `null` rings with the system default; [RINGTONE_SILENT] suppresses the ringtone. */
    val customRingtone: String? = null
) {
    val name: Name
        get() = details.names.firstOrNull() ?: Name(0, "", "", "", "", "")

    val photo: Photo?
        get() = details.photos.firstOrNull()

    val org: Organization
        get() = details.orgs.firstOrNull() ?: Organization(0, "")

    val nickname: Nickname
        get() = details.nicknames.firstOrNull { it.type == CDKNickname.TYPE_DEFAULT }
            ?: Nickname(0, "", CDKNickname.TYPE_DEFAULT)

    val birthday: Event?
        get() = details.dates.firstOrNull { it.type == CDKEvent.TYPE_BIRTHDAY }

    val note: Note
        get() = details.notes.firstOrNull() ?: Note(0, "")

    /** Returns false if the provider rejected the batch; never throws. */
    fun save(context: Context, newDetails: ContactDetails, oldDetails: ContactDetails): Boolean =
        saveWithOutcome(context, newDetails, oldDetails) == SaveOutcome.SAVED

    /**
     * Like [save], but tells a provider refusal of the target account apart from other
     * failures: on Android 16+ a new contact in the device or a provider SIM account is
     * rejected while the default account is a cloud account (see [NewContactRestriction]).
     */
    fun saveWithOutcome(context: Context, newDetails: ContactDetails, oldDetails: ContactDetails): SaveOutcome {
        val ops = ArrayList<ContentProviderOperation>()
        if (id == 0L) {
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .withValue(ContactsContract.RawContacts.STARRED, if (isFavorite) 1 else 0)
                .withValue(ContactsContract.RawContacts.CUSTOM_RINGTONE, ringtoneToProvider(customRingtone))
                .build()

            ops += newDetails.all().map { createInsertOperation(it) }
        } else {
            // Favorite and ringtone
            ops += ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(id.toString()))
                .withValue(ContactsContract.RawContacts.STARRED, if (isFavorite) 1 else 0)
                .withValue(ContactsContract.RawContacts.CUSTOM_RINGTONE, ringtoneToProvider(customRingtone))
                .build()

            // details
            ops += handleDetailUpdates(oldDetails.all(), newDetails.all(), id.toString())
        }
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            SaveOutcome.SAVED
        } catch (e: android.content.OperationApplicationException) {
            Log.error("Contact", "Error saving contact", e)
            SaveOutcome.FAILED
        } catch (e: android.os.RemoteException) {
            Log.error("Contact", "Error saving contact", e)
            SaveOutcome.FAILED
        } catch (e: SecurityException) {
            Log.error("Contact", "Error saving contact", e)
            SaveOutcome.FAILED
        } catch (e: IllegalArgumentException) {
            Log.error("Contact", "Error saving contact", e)
            if (NewContactAccountPolicy.isCloudDefaultRejection(e)) {
                SaveOutcome.REJECTED_CLOUD_DEFAULT
            } else {
                SaveOutcome.FAILED
            }
        }
    }

    private fun handleDetailUpdates(
        oldDetails: List<ContactDetail<*>>,
        newDetails: List<ContactDetail<*>>,
        rawContactId: String
    ): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        val oldById = oldDetails.filter { it.id != 0L }.associateBy { it.id }
        val newById = newDetails.filter { it.id != 0L }.associateBy { it.id }
        for ((id, _) in oldById) {
            if (id !in newById) ops += createDeleteOperation(id)
        }
        for (detail in newDetails) {
            if (detail.id == 0L || detail.id !in oldById) {
                ops += createInsertOperation(detail, rawContactId)
            } else {
                ops += createUpdateOperation(detail)
            }
        }
        return ops
    }

    private fun createInsertOperation(
        detail: ContactDetail<*>,
        rawContactId: String? = null
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        if (rawContactId != null) {
            builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
        } else {
            builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        }

        return builder.completeOperation(detail, true)
    }

    private fun createUpdateOperation(detail: ContactDetail<*>): ContentProviderOperation {
        return ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(detail.id.toString()))
            .completeOperation(detail, false)
    }

    private fun createDeleteOperation(dataId: Long): ContentProviderOperation {
        return ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId.toString()))
            .build()
    }

    fun ContentProviderOperation.Builder.completeOperation(
        detail: ContactDetail<*>,
        isInsert: Boolean
    ): ContentProviderOperation {
        if (isInsert) {
            this.withValue(ContactsContract.Data.MIMETYPE, mimeTypeFor(detail))
        }
        return when (detail) {
            is PhoneNumber -> completePhoneOperation(detail)
            is Email -> completeEmailOperation(detail)
            is Address -> completeAddressOperation(detail)
            is Event -> completeEventOperation(detail)
            is Photo -> completePhotoOperation(detail)
            is Name -> completeNameOperation(detail)
            is Organization -> completeOrganizationOperation(detail)
            is Note -> completeNoteOperation(detail)
            is Nickname -> completeNicknameOperation(detail)
            is GroupMembership -> completeGroupMembershipOperation(detail)
            is Gender -> completeGenderOperation(detail)
            is CommPref -> completeCommPrefOperation(detail)
            else -> throw IllegalArgumentException("Unknown detail type")
        }
    }

    private fun mimeTypeFor(detail: ContactDetail<*>): String =
        when (detail) {
            is PhoneNumber -> CDKPhone.CONTENT_ITEM_TYPE
            is Email -> CDKEmail.CONTENT_ITEM_TYPE
            is Address -> CDKStructuredPostal.CONTENT_ITEM_TYPE
            is Event -> CDKEvent.CONTENT_ITEM_TYPE
            is Photo -> CDKPhoto.CONTENT_ITEM_TYPE
            is Name -> CDKSName.CONTENT_ITEM_TYPE
            is Organization -> CDKOrg.CONTENT_ITEM_TYPE
            is Note -> CDKNote.CONTENT_ITEM_TYPE
            is Nickname -> CDKNickname.CONTENT_ITEM_TYPE
            is GroupMembership -> CDKGroupMembership.CONTENT_ITEM_TYPE
            is Gender -> Gender.MIME_TYPE
            is CommPref -> CommPref.MIME_TYPE
            else -> throw IllegalArgumentException("Unknown detail type")
        }

    private fun ContentProviderOperation.Builder.completePhoneOperation(
        detail: PhoneNumber
    ): ContentProviderOperation = this
        .withValue(CDKPhone.NUMBER, detail.number)
        .withValue(CDKPhone.TYPE, detail.type)
        .withValue(CDKPhone.LABEL, detail.label)
        .build()

    private fun ContentProviderOperation.Builder.completeEmailOperation(
        detail: Email
    ): ContentProviderOperation = this
        .withValue(CDKEmail.ADDRESS, detail.address)
        .withValue(CDKEmail.TYPE, detail.type)
        .withValue(CDKEmail.LABEL, detail.label)
        .build()

    private fun ContentProviderOperation.Builder.completeAddressOperation(
        detail: Address
    ): ContentProviderOperation = this
        .withValue(CDKStructuredPostal.FORMATTED_ADDRESS, detail.formattedAddress)
        .withValue(CDKStructuredPostal.TYPE, detail.type)
        .withValue(CDKStructuredPostal.LABEL, detail.label)
        .build()

    private fun ContentProviderOperation.Builder.completeEventOperation(
        detail: Event
    ): ContentProviderOperation = this
        // Year-less dates go out as "--MM-DD" (the provider contract Google
        // Contacts and third-party readers parse); the internal 1604 sentinel
        // must never reach the provider. See toExternalDateString.
        .withValue(CDKEvent.START_DATE, detail.startDate.toExternalDateString())
        .withValue(CDKEvent.TYPE, detail.type)
        .withValue(CDKEvent.LABEL, detail.label)
        .build()

    private fun ContentProviderOperation.Builder.completePhotoOperation(
        detail: Photo
    ): ContentProviderOperation = this
        .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
        .withValue(CDKPhoto.PHOTO, Base64.decode(detail.photo))
        .build()

    private fun ContentProviderOperation.Builder.completeNameOperation(
        detail: Name
    ): ContentProviderOperation = this
        .withValue(CDKSName.PREFIX, detail.namePrefix)
        .withValue(CDKSName.GIVEN_NAME, detail.firstName)
        .withValue(CDKSName.MIDDLE_NAME, detail.middleName)
        .withValue(CDKSName.FAMILY_NAME, detail.lastName)
        .withValue(CDKSName.SUFFIX, detail.nameSuffix)
        .build()

    private fun ContentProviderOperation.Builder.completeOrganizationOperation(
        detail: Organization
    ): ContentProviderOperation = this
        .withValue(CDKOrg.COMPANY, detail.company)
        .build()

    private fun ContentProviderOperation.Builder.completeNoteOperation(
        detail: Note
    ): ContentProviderOperation = this
        .withValue(CDKNote.NOTE, detail.content)
        .build()

    private fun ContentProviderOperation.Builder.completeNicknameOperation(
        detail: Nickname
    ): ContentProviderOperation = this
        .withValue(CDKNickname.NAME, detail.nickname)
        .withValue(CDKNickname.TYPE, detail.type)
        .build()

    private fun ContentProviderOperation.Builder.completeGroupMembershipOperation(
        detail: GroupMembership
    ): ContentProviderOperation = this
        .withValue(CDKGroupMembership.GROUP_ROW_ID, detail.groupId)
        .build()

    private fun ContentProviderOperation.Builder.completeGenderOperation(
        detail: Gender
    ): ContentProviderOperation = this
        .withValue(ContactsContract.Data.DATA1, detail.code)
        .withValue(ContactsContract.Data.DATA2, detail.text)
        .build()

    private fun ContentProviderOperation.Builder.completeCommPrefOperation(
        detail: CommPref
    ): ContentProviderOperation = this
        .withValue(ContactsContract.Data.DATA1, detail.number)
        .withValue(ContactsContract.Data.DATA2, detail.platform.storageKey)
        .build()

    companion object {

        private fun processDetails(details: ContactDetails, displayName: String?): ContactDetails? {
            var d = if (details.names.isEmpty()) details.copy(names = listOf(Name(0, "", "", "", "", ""))) else details

            val name = d.names.first()
            if (name.firstName.isEmpty() && name.lastName.isEmpty()) {
                displayName ?: return null
                val parts = displayName.split(" ")
                if (parts.first().isEmpty() && parts.last().isEmpty()) return null
                d = d.copy(names = listOf(Name(name.id, "", parts.first(), "", parts.last(), "")))
            }

            if (d.orgs.isEmpty()) d = d.copy(orgs = listOf(Organization(0, "")))
            if (d.notes.isEmpty()) d = d.copy(notes = listOf(Note(0, "")))
            if (d.nicknames.none { it.type == CDKNickname.TYPE_DEFAULT })
                d = d.copy(nicknames = d.nicknames + Nickname(0, "", CDKNickname.TYPE_DEFAULT))

            return d
        }

        private data class RawContactInfo(
            val id: Long,
            val displayName: String?,
            val isFavorite: Boolean,
            val accountName: String?,
            val accountType: String?,
            val customRingtone: String?
        )

        /**
         * The provider spells "do not ring" as an empty CUSTOM_RINGTONE and "system default"
         * as NULL, so translate between that and the shared [RINGTONE_SILENT] sentinel.
         */
        private fun ringtoneFromProvider(stored: String?): String? =
            if (stored != null && stored.isEmpty()) RINGTONE_SILENT else stored

        private fun ringtoneToProvider(value: String?): String? =
            if (value == RINGTONE_SILENT) "" else value

        private fun queryRawContacts(
            contentResolver: android.content.ContentResolver,
            contactId: Long?,
            projection: Array<String>,
            rawContacts: MutableList<RawContactInfo>,
        ) {
            contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                projection,
                buildString {
                    append("${ContactsContract.RawContacts.DELETED} = 0")
                    if (contactId != null) {
                        append(" AND ${ContactsContract.RawContacts._ID} = ?")
                    }
                },
                contactId?.let { arrayOf(it.toString()) },
                null,
            )?.use { cursor ->
                collectRawContacts(cursor, rawContacts)
            }
        }

        private fun collectRawContacts(
            cursor: android.database.Cursor,
            rawContacts: MutableList<RawContactInfo>,
        ) {
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val nameIdx =
                cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY)
            val starredIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.STARRED)
            val accountNameIdx =
                cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
            val accountTypeIdx =
                cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val ringtoneIdx =
                cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CUSTOM_RINGTONE)

            while (cursor.moveToNext()) {
                rawContacts += RawContactInfo(
                    id = cursor.getLong(idIdx),
                    displayName = cursor.getStringOrNull(nameIdx),
                    isFavorite = cursor.getInt(starredIdx) == 1,
                    accountName = cursor.getStringOrNull(accountNameIdx),
                    accountType = cursor.getStringOrNull(accountTypeIdx),
                    customRingtone = ringtoneFromProvider(cursor.getStringOrNull(ringtoneIdx))
                )
            }
        }

        private fun getContacts(context: Context, contactId: Long?): List<Contact> {
            val contentResolver = context.contentResolver
            val projection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.STARRED,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.CUSTOM_RINGTONE,
            )

            val rawContacts = mutableListOf<RawContactInfo>()
            try {
                // DELETED = 0 matters even though delete() hard-deletes: an account with a real
                // sync adapter legitimately leaves a tombstone until that adapter runs, and a
                // tombstoned row still carries DISPLAY_NAME_PRIMARY while its Data rows are
                // already gone. Without this filter processDetails() rebuilds a name-only
                // contact from that column, so the contact appears to survive deletion and any
                // edit to it silently fails to persist.
                queryRawContacts(contentResolver, contactId, projection, rawContacts)
            } catch (e: android.database.SQLException) {
                Log.error("Contact", "Error querying contacts", e)
            } catch (e: IllegalArgumentException) {
                Log.error("Contact", "Error querying contacts", e)
            } catch (e: SecurityException) {
                Log.error("Contact", "Error querying contacts", e)
            }

            if (rawContacts.isEmpty()) return emptyList()

            val allDetails = getDetailsInternal(context, contactId)
            return rawContacts.mapNotNull { raw ->
                val details = processDetails(
                    allDetails[raw.id] ?: ContactDetails.empty(),
                    raw.displayName
                ) ?: return@mapNotNull null
                Contact(
                    raw.id,
                    raw.accountType,
                    raw.accountName,
                    raw.isFavorite,
                    details,
                    raw.customRingtone
                )
            }
        }

        fun getContact(context: Context, contactId: Long): Contact? = getContacts(context, contactId).firstOrNull()

        fun getAllContacts(context: Context): List<Contact> =
            getContacts(context, null)

        fun delete(context: Context, contact: Contact) {
            val resolver = context.contentResolver
            val rawUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, contact.id)
            // A plain delete only tombstones (DELETED=1) raw contacts that belong to an account
            // whose sync adapter is expected to finish the deletion. Custom/local accounts have no
            // sync adapter, so the row would linger (contact still shows, and edits to a deleted
            // row don't persist). Force a real delete via the sync-adapter URI for those.
            var accountName: String? = null
            var accountType: String? = null
            resolver.query(
                rawUri,
                arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) { accountName = c.getString(0); accountType = c.getString(1) } }

            // Only local/null accounts (blank type, or the app's LOCAL_ACCOUNT_TYPE) have no
            // sync adapter, so they need the sync-adapter hard-delete URI. Real synced
            // accounts (Google etc.) must use the plain tombstoning delete so the server
            // sync removes them everywhere.
            if (accountType.isNullOrBlank() || accountType == LOCAL_ACCOUNT_TYPE) {
                val syncUri = rawUri.buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName ?: "")
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType ?: "")
                    .build()
                resolver.delete(syncUri, null, null)
            } else {
                resolver.delete(rawUri, null, null)
            }
        }
    }
}

