package com.vayunmathur.contacts.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * Second-pass provider coverage: a fake ContactsProvider over [MatrixCursor] so
 * `Contact.getAllContacts` / `getDetails` run their real cursor-parsing code.
 */
@RunWith(RobolectricTestRunner::class)
class FakeProviderPass2Test {

    class FakeContactsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            // lookupContact requests a null projection and indexes columns by name
            val cols = projection ?: if (uri.toString().contains("lookup")) {
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY,
                    "contact_id",
                )
            } else {
                defaultColumns(uri)
            }
            val cursor = MatrixCursor(cols)
            fun addRow(values: Map<String, Any?>) {
                cursor.addRow(cols.map { values[it] })
            }
            when {
                uri.toString().contains("raw_contacts") -> {
                    // selectionArgs carries the requested raw id for getContact
                    val wanted = selectionArgs?.firstOrNull()?.toLongOrNull()
                    for ((id, name, starred, accountName, accountType, ringtone) in RAW_CONTACTS) {
                        if (wanted != null && wanted != id) continue
                        addRow(
                            mapOf(
                                ContactsContract.RawContacts._ID to id,
                                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY to name,
                                ContactsContract.RawContacts.STARRED to starred,
                                ContactsContract.RawContacts.ACCOUNT_NAME to accountName,
                                ContactsContract.RawContacts.ACCOUNT_TYPE to accountType,
                                ContactsContract.RawContacts.CUSTOM_RINGTONE to ringtone,
                                ContactsContract.RawContacts.CONTACT_ID to id,
                            )
                        )
                    }
                }
                uri.toString().contains("lookup") -> {
                    // Contacts.lookupContact reads CONTACT_ID off the lookup URI
                    val row = HashMap<String, Any?>()
                    row[ContactsContract.Contacts._ID] = 7L
                    row["contact_id"] = 7L
                    row[ContactsContract.Contacts.LOOKUP_KEY] = "k7"
                    addRow(row)
                }
                uri.toString().contains("groups") -> {
                    addRow(mapOf(ContactsContract.Groups._ID to 11L, ContactsContract.Groups.TITLE to "Family"))
                    addRow(mapOf(ContactsContract.Groups._ID to 12L, ContactsContract.Groups.TITLE to null))
                }
                uri.toString().contains("data") -> {
                    val wanted = selectionArgs?.firstOrNull()?.toLongOrNull()
                    for (row in DATA_ROWS) {
                        if (wanted != null && wanted != row[ContactsContract.Data.RAW_CONTACT_ID]) continue
                        addRow(row)
                    }
                }
            }
            return cursor
        }

        private fun defaultColumns(uri: Uri): Array<String> = when {
            uri.toString().contains("raw_contacts") -> arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.STARRED,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.CUSTOM_RINGTONE,
            )
            uri.toString().contains("groups") -> arrayOf(
                ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
            )
            else -> DETAILS_COLUMNS
        }

        private var nextId = 1000L

        override fun insert(uri: Uri, values: ContentValues?): Uri =
            android.content.ContentUris.withAppendedId(uri, nextId++)

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun getType(uri: Uri): String? = null
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun register() {
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeContactsProvider()
        )
    }

    @Test
    fun getAllContactsParsesRawContactsAndDetails() {
        register()
        val contacts = Contact.getAllContacts(ctx)
        assertEquals(2, contacts.size)

        val aisha = contacts.first { it.id == 7L }
        assertEquals("Aisha", aisha.name.firstName)
        assertEquals("Rahman", aisha.name.lastName)
        assertTrue(aisha.isFavorite)
        assertEquals("a@gmail.com", aisha.accountName)
        assertEquals("com.google", aisha.accountType)
        assertEquals(listOf("+14155550102"), aisha.details.phoneNumbers.map { it.number })
        assertEquals(listOf("aisha@x.com"), aisha.details.emails.map { it.address })
        assertEquals("1200 Market St, Springfield, IL, 62701, USA",
            aisha.details.addresses.single().formattedAddress)
        assertEquals("Acme", aisha.org.company)
        assertEquals("Prefers email", aisha.note.content)
        assertEquals("Ash", aisha.details.nicknames.first { it.nickname.isNotBlank() }.nickname)
        assertEquals(listOf(11L), aisha.details.groups.map { it.groupId })
        assertEquals(2, aisha.details.dates.size) // birthday + no-year anniversary; garbage row skipped
        assertEquals(1992, aisha.details.dates.first { it.type == CDKEvent.TYPE_BIRTHDAY }.startDate.year)
        assertEquals(1, aisha.details.photos.size) // DATA15 blob -> base64 photo
        assertEquals("F", aisha.details.genders.single().code)

        val bob = contacts.first { it.id == 8L }
        // empty CUSTOM_RINGTONE from the provider means "silent"
        assertEquals(com.vayunmathur.library.ui.RINGTONE_SILENT, bob.customRingtone)
        // no detail rows: name is rebuilt from DISPLAY_NAME_PRIMARY
        assertEquals("Bob", bob.name.firstName)
        assertEquals("Lee", bob.name.lastName)
        assertTrue(bob.details.phoneNumbers.isEmpty())
    }

    @Test
    fun getContactByIdAndMissingId() {
        register()
        val aisha = assertNotNull(Contact.getContact(ctx, 7L))
        assertEquals("Aisha", aisha.name.firstName)
        assertNull(Contact.getContact(ctx, 999L))
    }

    @Test
    fun getDetailsForSingleAndUnknownContact() {
        register()
        val details = getDetails(ctx, 7L)
        assertEquals(listOf("+14155550102"), details.phoneNumbers.map { it.number })
        assertEquals(1, details.dates.count { it.type == CDKEvent.TYPE_BIRTHDAY })
        assertTrue(details.addresses.single().formattedAddress.contains("Springfield"))

        val unknown = getDetails(ctx, 12345L)
        assertEquals(ContactDetails.empty(), unknown)

        val all = getDetailsInternal(ctx)
        assertEquals(setOf(7L), all.keys)
    }

    @Test
    fun noYearBirthdayAndBadDatesAreHandled() {
        register()
        val details = getDetails(ctx, 7L)
        // the --03-15 no-year event lands on the 1604 sentinel year
        val noYear = details.dates.first { it.startDate.year == 1604 }
        assertEquals(3, noYear.startDate.monthNumber)
        assertEquals(15, noYear.startDate.dayOfMonth)
        // the garbage-date row was skipped, not crashed on
        assertEquals(2, details.dates.size)
    }

    @Test
    fun loadFullSizePhotoWithoutStreamReturnsNull() {
        register()
        assertNull(loadFullSizePhoto(ctx, 7L))
    }

    companion object {
        private val DETAILS_COLUMNS = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data._ID,
            ContactsContract.Data.MIMETYPE, ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9, ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA15,
        )

        private val RAW_CONTACTS = listOf(
            // id, display name, starred, account name, account type, ringtone
            RawRow(7L, "Aisha Rahman", 1, "a@gmail.com", "com.google", null),
            RawRow(8L, "Bob Lee", 0, null, null, ""),
        )

        private data class RawRow(
            val id: Long, val name: String, val starred: Int,
            val accountName: String?, val accountType: String?, val ringtone: String?,
        )

        private fun dataRow(
            rawId: Long, dataId: Long, mime: String,
            d1: Any? = null, d2: Any? = null, d3: Any? = null, d4: Any? = null,
            d5: Any? = null, d6: Any? = null, d7: Any? = null, d8: Any? = null,
            d9: Any? = null, d10: Any? = null, d15: Any? = null,
        ): Map<String, Any?> = mapOf(
            ContactsContract.Data.RAW_CONTACT_ID to rawId,
            ContactsContract.Data._ID to dataId,
            ContactsContract.Data.MIMETYPE to mime,
            ContactsContract.Data.CONTACT_ID to rawId,
            ContactsContract.Data.DATA1 to d1, ContactsContract.Data.DATA2 to d2,
            ContactsContract.Data.DATA3 to d3, ContactsContract.Data.DATA4 to d4,
            ContactsContract.Data.DATA5 to d5, ContactsContract.Data.DATA6 to d6,
            ContactsContract.Data.DATA7 to d7, ContactsContract.Data.DATA8 to d8,
            ContactsContract.Data.DATA9 to d9, ContactsContract.Data.DATA10 to d10,
            ContactsContract.Data.DATA15 to d15,
        )

        private val DATA_ROWS: List<Map<String, Any?>> = listOf(
            // StructuredName: DATA2=given, DATA3=family, DATA4=prefix, DATA5=middle, DATA6=suffix
            dataRow(7, 100, CDKSName.CONTENT_ITEM_TYPE, d2 = "Aisha", d3 = "Rahman"),
            dataRow(7, 101, CDKPhone.CONTENT_ITEM_TYPE, d1 = "+14155550102", d2 = CDKPhone.TYPE_MOBILE),
            dataRow(7, 102, CDKEmail.CONTENT_ITEM_TYPE, d1 = "aisha@x.com", d2 = CDKEmail.TYPE_HOME),
            // address with blank DATA1: joined from parts DATA4 street, DATA7 city, DATA8 region, DATA9 code, DATA10 country
            dataRow(7, 103, CDKStructuredPostal.CONTENT_ITEM_TYPE, d2 = CDKStructuredPostal.TYPE_HOME,
                d4 = "1200 Market St", d7 = "Springfield", d8 = "IL", d9 = "62701", d10 = "USA"),
            dataRow(7, 104, CDKEvent.CONTENT_ITEM_TYPE, d1 = "1992-03-15", d2 = CDKEvent.TYPE_BIRTHDAY),
            dataRow(7, 105, CDKEvent.CONTENT_ITEM_TYPE, d1 = "--03-15", d2 = CDKEvent.TYPE_ANNIVERSARY),
            dataRow(7, 106, CDKEvent.CONTENT_ITEM_TYPE, d1 = "not-a-date", d2 = CDKEvent.TYPE_OTHER),
            dataRow(7, 107, CDKOrg.CONTENT_ITEM_TYPE, d1 = "Acme"),
            dataRow(7, 108, CDKNote.CONTENT_ITEM_TYPE, d1 = "Prefers email"),
            dataRow(7, 109, CDKNickname.CONTENT_ITEM_TYPE, d1 = "Ash", d2 = CDKNickname.TYPE_DEFAULT),
            dataRow(7, 110, CDKGroupMembership.CONTENT_ITEM_TYPE, d1 = 11L),
            dataRow(7, 111, CDKPhoto.CONTENT_ITEM_TYPE, d15 = byteArrayOf(1, 2, 3, 4)),
            dataRow(7, 112, Gender.MIME_TYPE, d1 = "F", d2 = ""),
            dataRow(7, 113, "application/x-unknown", d1 = "ignored"),
            // platform rows read by PackageUtils.getContactPlatforms
            dataRow(7, 201, "vnd.android.cursor.item/vnd.com.whatsapp.voip_call"),
            dataRow(7, 202, "vnd.android.cursor.item/vnd.com.whatsapp.video_call"),
            dataRow(7, 203, "vnd.android.cursor.item/vnd.com.whatsapp.profile"),
            dataRow(7, 204, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.video"),
            dataRow(7, 205, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call"),
            dataRow(7, 206, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact"),
            dataRow(7, 207, "vnd.android.cursor.item/vnd.org.telegram.messenger.video"),
            dataRow(7, 208, "vnd.android.cursor.item/vnd.org.telegram.messenger.call"),
            dataRow(7, 209, "vnd.android.cursor.item/vnd.org.telegram.messenger.profile"),
        )
    }
}
