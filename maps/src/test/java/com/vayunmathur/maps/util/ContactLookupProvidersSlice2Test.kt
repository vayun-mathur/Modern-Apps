package com.vayunmathur.maps.util

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Postal-address reads over a fake contacts provider: session URIs, postal
 * rows and whole-contact resolution.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 *
 * A fake [ContentProvider] serves [MatrixCursor] rows (Robolectric's
 * `ShadowContentResolver.setCursor` only accepts the stub cursor, whose
 * accessors throw — a provider is the data-bearing path).
 */
@RunWith(RobolectricTestRunner::class)
class ContactLookupProvidersSlice2Test {

    class FakeContactsProvider : ContentProvider() {
        /** uri string -> rows served for it. */
        var tables: Map<String, List<Array<Any?>>> = emptyMap()

        private val columns = arrayOf(
            ContactsContract.Data.MIMETYPE,
            StructuredPostal.FORMATTED_ADDRESS,
            StructuredPostal.STREET,
            StructuredPostal.CITY,
            StructuredPostal.REGION,
            StructuredPostal.POSTCODE,
            StructuredPostal.COUNTRY,
            ContactsContract.Contacts._ID,
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            val rows = tables[uri.toString()].orEmpty()
            val cursor = MatrixCursor(columns, rows.size)
            rows.forEach { cursor.addRow(it) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var provider: FakeContactsProvider

    private fun sessionUri() = Uri.parse("content://com.android.contacts/session/1")
    private fun rowUri() = Uri.parse("content://com.android.contacts/data/7")
    private fun contactUri() = Uri.parse("content://com.android.contacts/contacts/9")

    private fun postalRow(
        formatted: String? = "1 Ferry Building\nSan Francisco, CA 94111",
        street: String? = null,
        city: String? = null,
        region: String? = null,
        postcode: String? = null,
        country: String? = null,
        mime: String? = StructuredPostal.CONTENT_ITEM_TYPE,
    ): Array<Any?> = arrayOf(mime, formatted, street, city, region, postcode, country, "9")

    private fun setUp() {
        provider = Robolectric.setupContentProvider(
            FakeContactsProvider::class.java,
            "com.android.contacts",
        )
    }

    @Test
    fun `a formatted session address normalises to one line`() {
        setUp()
        provider.tables = mapOf(sessionUri().toString() to listOf(postalRow()))
        val addresses = readSessionAddresses(context, sessionUri())
        assertEquals(
            listOf("1 Ferry Building, San Francisco, CA 94111"),
            addresses,
        )
    }

    @Test
    fun `non-postal session rows are skipped`() {
        setUp()
        provider.tables = mapOf(
            sessionUri().toString() to listOf(
                postalRow(mime = "vnd.android.cursor.item/phone_v2", formatted = "555-0102"),
                postalRow(),
            )
        )
        assertEquals(1, readSessionAddresses(context, sessionUri()).size)
    }

    @Test
    fun `duplicate session addresses are de-duplicated`() {
        setUp()
        provider.tables = mapOf(sessionUri().toString() to listOf(postalRow(), postalRow()))
        assertEquals(1, readSessionAddresses(context, sessionUri()).size)
    }

    @Test
    fun `an empty session reads empty`() {
        setUp()
        provider.tables = emptyMap()
        assertEquals(emptyList(), readSessionAddresses(context, sessionUri()))
    }

    @Test
    fun `a postal row reads its formatted address`() {
        setUp()
        provider.tables = mapOf(rowUri().toString() to listOf(postalRow()))
        assertEquals(
            "1 Ferry Building, San Francisco, CA 94111",
            readAddress(context, rowUri()),
        )
    }

    @Test
    fun `a row without address parts reads null`() {
        setUp()
        provider.tables = mapOf(
            rowUri().toString() to listOf(
                postalRow(formatted = null, street = null, city = null, region = null, postcode = null, country = null)
            )
        )
        assertNull(readAddress(context, rowUri()))
    }

    @Test
    fun `components compose when no formatted address exists`() {
        setUp()
        provider.tables = mapOf(
            rowUri().toString() to listOf(
                postalRow(
                    formatted = null,
                    street = "1 Ferry Building",
                    city = "San Francisco",
                    region = "CA",
                    postcode = "94111",
                    country = "USA",
                )
            )
        )
        assertEquals(
            "1 Ferry Building, San Francisco, CA, 94111, USA",
            readAddress(context, rowUri()),
        )
    }

    @Test
    fun `a missing row reads null`() {
        setUp()
        provider.tables = emptyMap()
        assertNull(readAddress(context, rowUri()))
    }

    @Test
    fun `a whole contact resolves its postal rows`() {
        setUp()
        // The id lookup reads column 0 of the contact row; the data query
        // then serves the postal rows for that contact.
        provider.tables = mapOf(
            contactUri().toString() to listOf(
                arrayOf<Any?>("9", null, null, null, null, null, null, "9"),
            ),
            ContactsContract.Data.CONTENT_URI.toString() to listOf(postalRow()),
        )
        val addresses = readContactAddresses(context, contactUri())
        assertEquals(
            listOf("1 Ferry Building, San Francisco, CA 94111"),
            addresses,
        )
    }

    @Test
    fun `a contact with no rows resolves empty`() {
        setUp()
        provider.tables = emptyMap()
        assertEquals(emptyList(), readContactAddresses(context, contactUri()))
    }
}
