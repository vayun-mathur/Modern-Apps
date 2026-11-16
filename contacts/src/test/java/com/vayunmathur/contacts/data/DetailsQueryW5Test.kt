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
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** W5: getDetails over a provider serving every mimetype, including edge rows. */
@RunWith(RobolectricTestRunner::class)
class DetailsQueryW5Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    class EdgeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = projection ?: COLUMNS
            val cursor = MatrixCursor(cols)
            if (uri.toString().contains("data")) {
                for (row in ROWS) cursor.addRow(cols.map { row[it] })
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        companion object {
            val COLUMNS = arrayOf(
                ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE, ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
                ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
                ContactsContract.Data.DATA10, ContactsContract.Data.DATA15,
            )

            private fun row(
                id: Long, mime: String, d1: Any? = null, d2: Any? = null, d3: Any? = null,
                d4: Any? = null, d5: Any? = null, d6: Any? = null, d7: Any? = null,
                d8: Any? = null, d9: Any? = null, d10: Any? = null, d15: Any? = null,
            ): Map<String, Any?> = mapOf(
                ContactsContract.Data.RAW_CONTACT_ID to 7L,
                ContactsContract.Data._ID to id,
                ContactsContract.Data.MIMETYPE to mime,
                ContactsContract.Data.CONTACT_ID to 7L,
                ContactsContract.Data.DATA1 to d1, ContactsContract.Data.DATA2 to d2,
                ContactsContract.Data.DATA3 to d3, ContactsContract.Data.DATA4 to d4,
                ContactsContract.Data.DATA5 to d5, ContactsContract.Data.DATA6 to d6,
                ContactsContract.Data.DATA7 to d7, ContactsContract.Data.DATA8 to d8,
                ContactsContract.Data.DATA9 to d9, ContactsContract.Data.DATA10 to d10,
                ContactsContract.Data.DATA15 to d15,
            )

            val ROWS = listOf(
                row(1, CDKPhone.CONTENT_ITEM_TYPE, "+14155550102", 2, null),
                row(2, CDKEmail.CONTENT_ITEM_TYPE, "aisha@x.com", 1, null),
                // Address with no formatted form: parts must be joined.
                row(3, CDKStructuredPostal.CONTENT_ITEM_TYPE, "", 1, null, "1200 Market St", null, null, "San Francisco", "CA", "94105", "USA"),
                row(4, CDKEvent.CONTENT_ITEM_TYPE, "--03-15", 1, null),
                row(5, CDKEvent.CONTENT_ITEM_TYPE, "1992-03-15", 1, null),
                row(6, CDKEvent.CONTENT_ITEM_TYPE, "not-a-date", 2, null),
                row(9, CDKSName.CONTENT_ITEM_TYPE, null, "Aisha", "Rahman", "Dr", "M", "PhD"),
                row(10, CDKOrg.CONTENT_ITEM_TYPE, "Acme Design"),
                row(11, CDKNote.CONTENT_ITEM_TYPE, "Prefers email"),
                row(12, CDKNickname.CONTENT_ITEM_TYPE, "Ash", 1),
                row(13, CDKGroupMembership.CONTENT_ITEM_TYPE, 11L),
                row(14, Gender.MIME_TYPE, "F", "Female"),
                // Comm prefs: one valid, one unknown platform, one blank number.
                row(15, CommPref.MIME_TYPE, "+14155550102", "whatsapp"),
                row(16, CommPref.MIME_TYPE, "+14155550102", "carrier-pigeon"),
                row(17, CommPref.MIME_TYPE, "  ", "whatsapp"),
                row(18, "application/x-unknown-mimetype", "ignored"),
                // Photos last: the no-blob fallback opens a photo stream this
                // provider cannot serve, which ends the scan (caught by the caller).
                row(7, CDKPhoto.CONTENT_ITEM_TYPE, null, null, null, d15 = byteArrayOf(1, 2, 3)),
                row(8, CDKPhoto.CONTENT_ITEM_TYPE, null, null, null, d15 = null),
            )
        }
    }

    @Test
    fun getDetailsParsesEveryRowTypeAndDropsBadRows() {
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, EdgeProvider())
        val details = getDetails(ctx, 7L)
        assertEquals(listOf("+14155550102"), details.phoneNumbers.map { it.number })
        assertEquals(listOf("aisha@x.com"), details.emails.map { it.address })
        assertEquals(
            listOf("1200 Market St, San Francisco, CA, 94105, USA"),
            details.addresses.map { it.formattedAddress },
        )
        // No-year and full dates both parse; the garbage date is dropped.
        assertEquals(
            listOf(LocalDate(1604, 3, 15), LocalDate(1992, 3, 15)),
            details.dates.map { it.startDate },
        )
        assertEquals(1, details.photos.size, "only the blob photo should survive, got ${details.photos}")
        assertEquals("Aisha", details.names.single().firstName)
        assertEquals("Rahman", details.names.single().lastName)
        assertEquals("Acme Design", details.orgs.single().company)
        assertEquals("Prefers email", details.notes.single().content)
        assertEquals("Ash", details.nicknames.single().nickname)
        assertEquals(listOf(11L), details.groups.map { it.groupId })
        assertEquals("F", details.genders.single().code)
        // Only the valid comm pref survives.
        assertEquals(1, details.commPrefs.size)
        assertEquals(CommPlatform.WHATSAPP, details.commPrefs.single().platform)
        assertNotNull(details)
    }

    @Test
    fun getDetailsWithEmptyProviderIsEmptyAndPhotoLoadIsNull() {
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, EmptyProvider())
        assertEquals(ContactDetails.empty(), getDetails(ctx, 7L))
        assertNull(loadFullSizePhoto(ctx, 7L))
    }

    @Test
    fun profileDetailsAlsoParse() {
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, EdgeProvider())
        assertTrue(getDetails(ctx, 7L, isProfile = true).phoneNumbers.isNotEmpty())
    }

    class EmptyProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: EdgeProvider.COLUMNS)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
