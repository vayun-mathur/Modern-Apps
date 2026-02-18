package com.vayunmathur.contacts

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Profile
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime


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
    val nicknames: List<Nickname>
) {
    fun all(): List<ContactDetail<*>> {
        return phoneNumbers + emails + addresses + dates + photos + names + orgs + notes + nicknames
    }

    companion object {
        fun empty(): ContactDetails {
            return ContactDetails(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
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

interface ContactDetail<T: ContactDetail<T>> {
    val id: Long
    val type: Int
    val value: String
    fun withType(type: Int): T
    fun withValue(value: String): T
    fun typeString(context: Context): String

    companion object {
        @OptIn(ExperimentalTime::class)
        inline fun <reified T: ContactDetail<T>> default(): T {
            return when (T::class) {
                PhoneNumber::class -> PhoneNumber(0, "", CDKPhone.TYPE_MOBILE)
                Email::class -> Email(0, "", CDKEmail.TYPE_HOME)
                Address::class -> Address(0, "", CDKStructuredPostal.TYPE_HOME)
                Event::class -> Event(0, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date, CDKEvent.TYPE_OTHER)
                else -> throw IllegalArgumentException("Unknown type")
            } as T
        }
    }
}

@Serializable
data class PhoneNumber(override val id: Long, val number: String, override val type: Int): ContactDetail<PhoneNumber> {
    override val value: String
        get() = number

    override fun withType(type: Int) = PhoneNumber(id, number, type)
    override fun withValue(value: String) = PhoneNumber(id, value, type)

    override fun typeString(context: Context) = CDKPhone.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Email(override val id: Long, val address: String, override val type: Int): ContactDetail<Email> {
    override val value: String
        get() = address

    override fun withType(type: Int) = Email(id, address, type)
    override fun withValue(value: String) = Email(id, value, type)

    override fun typeString(context: Context) = CDKEmail.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Address(override val id: Long, val formattedAddress: String, override val type: Int): ContactDetail<Address> {
    override val value: String
        get() = formattedAddress

    override fun withType(type: Int) = Address(id, formattedAddress, type)
    override fun withValue(value: String) = Address(id, value, type)

    override fun typeString(context: Context) = CDKStructuredPostal.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Photo(override val id: Long, val photo: String): ContactDetail<Photo> {
    override val type: Int = 0
    override val value: String
        get() = photo

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of photo")
    override fun withValue(value: String) = Photo(id, value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Photo doesn't have type")
}

@Serializable
data class Event(override val id: Long, val startDate: LocalDate, override val type: Int): ContactDetail<Event> {
    override val value: String
        get() = startDate.format(LocalDate.Formats.ISO)

    override fun withType(type: Int) = Event(id, startDate, type)
    override fun withValue(value: String) = Event(id, LocalDate.parse(value), type)

    override fun typeString(context: Context) = CDKEvent.getTypeLabel(context.resources, type, "").toString()
}

@Serializable
data class Organization(override val id: Long, val company: String): ContactDetail<Organization> {
    override val type: Int = 0
    override val value: String
        get() = company

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of photo")
    override fun withValue(value: String) = Organization(id, value)

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
        get() = listOfNotNull(namePrefix.ifEmpty { null }, firstName.ifEmpty { null }, middleName.ifEmpty { null }, lastName.ifEmpty { null }, nameSuffix.ifEmpty { null }).joinToString(" ")

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of name")
    override fun withValue(value: String) = throw UnsupportedOperationException("Cannot change value of name")

    override fun typeString(context: Context) = throw UnsupportedOperationException("Name doesn't have type")
}

@Serializable
data class Note(override val id: Long, val content: String): ContactDetail<Note> {
    override val type: Int = 0
    override val value: String
        get() = content

    override fun withType(type: Int) = throw UnsupportedOperationException("Cannot change type of note")
    override fun withValue(value: String) = copy(content = value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Note doesn't have type")
}

@Serializable
data class Nickname(override val id: Long, val nickname: String, override val type: Int): ContactDetail<Nickname> {
    override val value: String
        get() = nickname

    override fun withType(type: Int) = copy(type = type)
    override fun withValue(value: String) = copy(nickname = value)

    override fun typeString(context: Context) = throw UnsupportedOperationException("Nickname types shouldn't be written")
}

@Serializable
data class Contact(
    val id: Long,
    val isFavorite: Boolean,
    val details: ContactDetails
) {
    val name: Name
        get() = details.names.first()

    val photo: Photo?
        get() = details.photos.firstOrNull()

    val org: Organization
        get() = details.orgs.first()

    val nickname: Nickname
        get() = details.nicknames.first { it.type == CDKNickname.TYPE_DEFAULT }

    val birthday: Event?
        get() = details.dates.firstOrNull { it.type == CDKEvent.TYPE_BIRTHDAY }

    val note: Note
        get() = details.notes.first()

    fun save(context: Context, newDetails: ContactDetails, oldDetails: ContactDetails) {
        val ops = ArrayList<ContentProviderOperation>()
        if (id == 0L) {
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()

            ops += details.all().map { createInsertOperation(it) }
        } else {
            // Favorite
            ops += ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.Contacts._ID} = ?", arrayOf(id.toString()))
                .withValue(ContactsContract.RawContacts.STARRED, if (isFavorite) 1 else 0)
                .build()

            // details
            ops += handleDetailUpdates(oldDetails.all(), newDetails.all(), id.toString())
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun handleDetailUpdates(currentDetails: List<ContactDetail<*>>, newDetails: List<ContactDetail<*>>, rawContactID: String): List<ContentProviderOperation> {
        val currentIds = currentDetails.map { it.id }.toSet()
        val newIds = newDetails.map { it.id }.toSet()
        val ops = mutableListOf<ContentProviderOperation>()

        val idsToDelete = currentIds - newIds
        ops += idsToDelete.map { id -> createDeleteOperation(id)  }

        ops += newDetails.mapNotNull { detail ->
            if (detail.id == 0L) { // New item
                createInsertOperation(detail, rawContactID)
            } else { // Existing item, check if it has changed
                val oldDetail = currentDetails.find { it.id == detail.id }
                if (oldDetail != null && oldDetail != detail) {
                    createUpdateOperation(detail)
                } else null
            }
        }

        return ops
    }

    private fun createInsertOperation(detail: ContactDetail<*>, rawContactId: String? = null): ContentProviderOperation {
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

    fun ContentProviderOperation.Builder.completeOperation(detail: ContactDetail<*>, isInsert: Boolean): ContentProviderOperation {
        if (isInsert) {
            this.withValue(ContactsContract.Data.MIMETYPE, when(detail) {
                is PhoneNumber -> CDKPhone.CONTENT_ITEM_TYPE
                is Email -> CDKEmail.CONTENT_ITEM_TYPE
                is Address -> CDKStructuredPostal.CONTENT_ITEM_TYPE
                is Event -> CDKEvent.CONTENT_ITEM_TYPE
                is Photo -> CDKPhoto.CONTENT_ITEM_TYPE
                is Name -> CDKSName.CONTENT_ITEM_TYPE
                is Organization -> CDKOrg.CONTENT_ITEM_TYPE
                is Note -> CDKNote.CONTENT_ITEM_TYPE
                is Nickname -> CDKNickname.CONTENT_ITEM_TYPE
                else -> throw IllegalArgumentException("Unknown detail type")
            })
        }
        return when (detail) {
            is PhoneNumber -> this
                .withValue(CDKPhone.NUMBER, detail.number)
                .withValue(CDKPhone.TYPE, detail.type)
                .build()
            is Email -> this
                .withValue(CDKEmail.ADDRESS, detail.address)
                .withValue(CDKEmail.TYPE, detail.type)
                .build()
            is Address -> this
                .withValue(CDKStructuredPostal.FORMATTED_ADDRESS, detail.formattedAddress)
                .withValue(CDKStructuredPostal.TYPE, detail.type)
                .build()
            is Event -> this
                .withValue(CDKEvent.START_DATE, detail.startDate.format(LocalDate.Formats.ISO))
                .withValue(CDKEvent.TYPE, detail.type)
                .build()
            is Photo -> this
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(CDKPhoto.PHOTO, Base64.decode(detail.photo))
                .build()
            is Name -> this
                .withValue(CDKSName.PREFIX, detail.namePrefix)
                .withValue(CDKSName.GIVEN_NAME, detail.firstName)
                .withValue(CDKSName.MIDDLE_NAME, detail.middleName)
                .withValue(CDKSName.FAMILY_NAME, detail.lastName)
                .withValue(CDKSName.SUFFIX, detail.nameSuffix)
                .build()
            is Organization -> this
                .withValue(CDKOrg.COMPANY, detail.company)
                .build()
            is Note -> this
                .withValue(CDKNote.NOTE, detail.content)
                .build()
            is Nickname -> this
                .withValue(CDKNickname.NAME, detail.nickname)
                .withValue(CDKNickname.TYPE, detail.type)
                .build()

            else -> throw IllegalArgumentException("Unknown detail type")
        }
    }

    companion object {

        private fun processDetails(details: ContactDetails, displayName: String): ContactDetails? {
            var details = details
            if(details.names.isEmpty())
                details = details.copy(names = listOf(Name(0, "", "", "", "", "")))

            if((details.names.first().firstName.isEmpty() && details.names.first().lastName.isEmpty())) {
                val firstName = displayName.split(" ").first()
                val lastName = displayName.split(" ").last()
                if(firstName.isEmpty() && lastName.isEmpty()) return null
                details = details.copy(names = listOf(Name(details.names.first().id, "", firstName, "", lastName, "")))
            }

            if(details.orgs.isEmpty())
                details = details.copy(orgs = listOf(Organization(0, "")))

            if(details.notes.isEmpty())
                details = details.copy(notes = listOf(Note(0, "")))

            if(details.nicknames.find { it.type == CDKNickname.TYPE_DEFAULT } == null)
                details = details.copy(nicknames = details.nicknames + Nickname(0, "", CDKNickname.TYPE_DEFAULT))

            return details
        }

        private fun getContacts(context: Context, contactId: Long?): List<Contact> {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.STARRED,
                ContactsContract.RawContacts.ACCOUNT_NAME
            )
            val cursor = contentResolver.query(uri, projection, if(contactId == null) null else "${ContactsContract.Contacts._ID} = ?", listOfNotNull(contactId?.toString()).toTypedArray(), null)

            val contacts = mutableListOf<Contact>()

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
                    val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY))
                    val isFavorite = it.getInt(it.getColumnIndexOrThrow(ContactsContract.RawContacts.STARRED)) == 1
                    val account = it.getStringOrNull(it.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME))
                    if(account != null) continue
                    println("ACCOUNT $account")

                    var details = getDetails(context, id, false)
                    details = processDetails(details, displayName) ?: continue

                    contacts += Contact(id, isFavorite, details)
                }
            }
            return contacts
        }

        fun getContact(context: Context, contactId: Long): Contact? = getContacts(context, contactId).firstOrNull()

        fun getAllContacts(context: Context): List<Contact> =
            getContacts(context, null)

        fun delete(context: Context, contact: Contact) {
            context.contentResolver.delete(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, contact.id),
                null, null
            )
        }
    }
}

fun getDetails(context: Context, id: Long, isProfile: Boolean = false): ContactDetails {
    val contentResolver = context.contentResolver
    val contactId = id.toString()

    fun <T: ContactDetail<T>> queryData(projection: List<String>, mimeType: String, datumFromCursor: (Cursor) -> T?): List<T> {
        val data = mutableListOf<T>()
        contentResolver.query(
            if(isProfile) Uri.withAppendedPath(Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY) else ContactsContract.Data.CONTENT_URI,
            projection.toTypedArray(),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, mimeType),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                datumFromCursor(cursor)?.let {
                    data.add(it)
                }
            }
        }
        return data
    }

    val phoneNumbers = queryData(listOf(CDKPhone._ID, CDKPhone.NUMBER, CDKPhone.TYPE), CDKPhone.CONTENT_ITEM_TYPE) {
        val id = it.getLong(it.getColumnIndexOrThrow(CDKPhone._ID))
        val number = it.getString(it.getColumnIndexOrThrow(CDKPhone.NUMBER))
        val type = it.getInt(it.getColumnIndexOrThrow(CDKPhone.TYPE))
        PhoneNumber(id, number, type)
    }

    val emails = queryData(listOf(CDKEmail._ID, CDKEmail.ADDRESS, CDKEmail.TYPE), CDKEmail.CONTENT_ITEM_TYPE) {
        val id = it.getLong(it.getColumnIndexOrThrow(CDKEmail._ID))
        val email = it.getString(it.getColumnIndexOrThrow(CDKEmail.ADDRESS))
        val type = it.getInt(it.getColumnIndexOrThrow(CDKEmail.TYPE))
        Email(id, email, type)
    }

    val projection = listOf(
        CDKStructuredPostal._ID,
        CDKStructuredPostal.FORMATTED_ADDRESS,
        CDKStructuredPostal.TYPE,
        CDKStructuredPostal.STREET,
        CDKStructuredPostal.CITY,
        CDKStructuredPostal.REGION,
        CDKStructuredPostal.POSTCODE,
        CDKStructuredPostal.COUNTRY
    )

    val addresses = queryData(projection, CDKStructuredPostal.CONTENT_ITEM_TYPE) { cursor ->
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(CDKStructuredPostal._ID))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CDKStructuredPostal.TYPE))

        // 2. Attempt to get the formatted address
        var formatted = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKStructuredPostal.FORMATTED_ADDRESS))

        // 3. Fallback logic: if formatted is null, build it from parts
        if (formatted.isNullOrBlank()) {
            val street = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKStructuredPostal.STREET))
            val city = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKStructuredPostal.CITY))
            val region = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKStructuredPostal.REGION))
            val code = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKStructuredPostal.POSTCODE))
            val country = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKStructuredPostal.COUNTRY))

            // Join non-null components with a comma or newline
            formatted = listOfNotNull(street, city, region, code, country)
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }

        Address(id, formatted, type)
    }

    // Dates
    val dates = queryData(listOf(CDKEvent._ID, CDKEvent.START_DATE, CDKEvent.TYPE), CDKEvent.CONTENT_ITEM_TYPE) { cursor ->
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(CDKEvent._ID))
        val date = cursor.getString(cursor.getColumnIndexOrThrow(CDKEvent.START_DATE))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CDKEvent.TYPE))
        val localDate = if(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            LocalDate.parse(date, LocalDate.Formats.ISO)
        } else if(date.matches(Regex("\\d{8}"))) {
            LocalDate.parse(date, LocalDate.Format { year(); monthNumber(); day() })
        } else {
            return@queryData null
        }
        Event(id,localDate, type)
    }

    // Photos
    val photos = queryData(listOf(CDKPhoto._ID, CDKPhoto.PHOTO), CDKPhoto.CONTENT_ITEM_TYPE) { cursor ->
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(CDKPhoto._ID))
        val photo = Base64.encode(cursor.getBlob(cursor.getColumnIndexOrThrow(CDKPhoto.PHOTO)))
        Photo(id, photo)
    }

    val names = queryData(listOf(
        CDKSName._ID,
        CDKSName.PREFIX,
        CDKSName.GIVEN_NAME,
        CDKSName.MIDDLE_NAME,
        CDKSName.FAMILY_NAME,
        CDKSName.SUFFIX
    ), CDKSName.CONTENT_ITEM_TYPE) { cursor ->
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(CDKSName._ID))
        val namePrefix = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKSName.PREFIX)) ?: ""
        val firstName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKSName.GIVEN_NAME)) ?: ""
        val middleName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKSName.MIDDLE_NAME)) ?: ""
        val lastName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKSName.FAMILY_NAME)) ?: ""
        val nameSuffix = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(CDKSName.SUFFIX)) ?: ""

        Name(id, namePrefix, firstName, middleName, lastName, nameSuffix)
    }

    val orgs = queryData(listOf(CDKOrg._ID, CDKOrg.COMPANY), CDKOrg.CONTENT_ITEM_TYPE) {
        val id = it.getLong(it.getColumnIndexOrThrow(CDKOrg._ID))
        val company = it.getString(it.getColumnIndexOrThrow(CDKOrg.COMPANY))
        Organization(id, company)
    }

    val note = queryData(listOf(CDKNote._ID, CDKNote.NOTE), CDKNote.CONTENT_ITEM_TYPE) {
        val id = it.getLong(it.getColumnIndexOrThrow(CDKNote._ID))
        val note = it.getString(it.getColumnIndexOrThrow(CDKNote.NOTE))
        Note(id, note)
    }

    val nicknames = queryData(listOf(CDKNickname._ID, CDKNickname.NAME, CDKNickname.TYPE), CDKNickname.CONTENT_ITEM_TYPE) {
        val id = it.getLong(it.getColumnIndexOrThrow(CDKNickname._ID))
        val nickname = it.getString(it.getColumnIndexOrThrow(CDKNickname.NAME))
        val type = it.getInt(it.getColumnIndexOrThrow(CDKNickname.TYPE))
        Nickname(id, nickname, type)
    }

    return ContactDetails(phoneNumbers.distinct(), emails.distinct(), addresses.distinct(), dates.distinct(), photos.distinct(), names.distinct(), orgs.distinct(), note.distinct(), nicknames.distinct())
}
