package com.vayunmathur.contacts.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * Second-pass SIM coverage: a fake ICC ADN provider so [SimContactsDataSource]
 * runs its real row parsing, projection and write paths.
 */
@RunWith(RobolectricTestRunner::class)
class SimProviderPass2Test {

    class FakeIccProvider(private val aliasColumns: Boolean = false) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = if (aliasColumns) ALIAS_COLUMNS else COLUMNS
            val cursor = MatrixCursor(cols)
            for (row in ROWS) {
                cursor.addRow(cols.map { row[it] })
            }
            lastQueryUri = uri
            return cursor
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            lastInsertUri = uri
            lastInsertValues = values
            return ContentUris.withAppendedId(uri, 99)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            lastDeleteUri = uri
            lastDeleteSelection = selection
            return 1
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            lastUpdateUri = uri
            lastUpdateValues = values
            return 1
        }

        override fun getType(uri: Uri): String? = null

        companion object {
            val COLUMNS = arrayOf("name", "number", "emails", "_id")
            val ALIAS_COLUMNS = arrayOf("tag", "newNumber", "email", "_id")
            var lastQueryUri: Uri? = null
            var lastInsertUri: Uri? = null
            var lastInsertValues: ContentValues? = null
            var lastUpdateUri: Uri? = null
            var lastUpdateValues: ContentValues? = null
            var lastDeleteUri: Uri? = null
            var lastDeleteSelection: String? = null

            private val ROWS: List<Map<String, Any?>> = listOf(
                mapOf("name" to "Aisha Rahman", "tag" to "Aisha Rahman", "number" to "+14155550102", "newNumber" to "+14155550102", "emails" to "aisha@x.com", "email" to "aisha@x.com", "_id" to 1L),
                // exact duplicate row: dedup must collapse it
                mapOf("name" to "Aisha Rahman", "tag" to "Aisha Rahman", "number" to "+14155550102", "newNumber" to "+14155550102", "emails" to "aisha@x.com", "email" to "aisha@x.com", "_id" to 1L),
                mapOf("name" to "", "tag" to "", "number" to "+442071234567", "newNumber" to "+442071234567", "emails" to "", "email" to "", "_id" to 2L),
                // fully blank row: skipped
                mapOf("name" to "  ", "tag" to "  ", "number" to "", "newNumber" to "", "emails" to null, "email" to null, "_id" to 3L),
                mapOf("name" to "Bob", "tag" to "Bob", "number" to "", "newNumber" to "", "emails" to null, "email" to null, "_id" to 4L),
            )
        }
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun register(aliasColumns: Boolean = false) {
        ShadowContentResolver.registerProviderInternal("icc", FakeIccProvider(aliasColumns))
    }

    @Test
    fun listSimContactsParsesAndDeduplicatesRows() {
        register()
        val contacts = SimContactsDataSource.listSimContacts(ctx)
        assertEquals(3, contacts.size)
        val aisha = contacts.first { it.name == "Aisha Rahman" }
        assertEquals("+14155550102", aisha.number)
        assertEquals("aisha@x.com", aisha.emails)
        assertEquals(1L, aisha.id)
        assertEquals(null, aisha.subscriptionId) // no active subscriptions under Robolectric
        val unnamed = contacts.first { it.number == "+442071234567" }
        assertEquals("", unnamed.name)
        assertEquals(null, unnamed.emails)
        assertEquals("content://icc/adn", FakeIccProvider.lastQueryUri.toString())
    }

    @Test
    fun listSimContactsUsesAliasColumnsWhenPrimaryMissing() {
        register(aliasColumns = true)
        val contacts = SimContactsDataSource.listSimContacts(ctx)
        assertEquals(3, contacts.size)
        assertEquals("+14155550102", contacts.first { it.name == "Aisha Rahman" }.number)
        assertEquals("aisha@x.com", contacts.first { it.name == "Aisha Rahman" }.emails)
    }

    @Test
    fun simContactsAsContactsProjectsIntoUnifiedModel() {
        register()
        val contacts = SimContactsDataSource.simContactsAsContacts(ctx)
        assertEquals(3, contacts.size)
        val aisha = contacts.first { it.name.firstName == "Aisha" }
        assertTrue(aisha.id < -1_000_000_000L, "synthetic id should be below the base, was ${aisha.id}")
        assertEquals(SIM_ACCOUNT_TYPE, aisha.accountType)
        assertEquals("", aisha.accountName) // no subscription id
        assertEquals("Rahman", aisha.name.lastName)
        assertEquals(listOf("+14155550102"), aisha.details.phoneNumbers.map { it.number })
        assertEquals(listOf("aisha@x.com"), aisha.details.emails.map { it.address })
        assertFalse(aisha.isFavorite)
    }

    @Test
    fun syntheticIdsAreStableAndDistinct() {
        val a = SimContact(1, "Aisha Rahman", "+1415", "a@x.com", 5)
        val aCopy = SimContact(99, "Aisha Rahman", "+1415", "a@x.com", 5)
        assertEquals(SimContactsDataSource.syntheticIdFor(a), SimContactsDataSource.syntheticIdFor(aCopy))
        assertNotEquals(
            SimContactsDataSource.syntheticIdFor(a),
            SimContactsDataSource.syntheticIdFor(a.copy(number = "+9999")),
        )
        assertNotEquals(
            SimContactsDataSource.syntheticIdFor(a),
            SimContactsDataSource.syntheticIdFor(a.copy(subscriptionId = 6)),
        )
        // unnamed / number-only contacts split cleanly
        val unnamed = SimContactsDataSource.simContactToContact(SimContact(-1, "", "+4420", null, null))
        assertEquals("", unnamed.name.firstName)
        assertEquals(listOf("+4420"), unnamed.details.phoneNumbers.map { it.number })
        assertTrue(unnamed.details.emails.isEmpty())
    }

    @Test
    fun findBackingSimContactByIdAndByFallback() {
        register()
        val listed = SimContactsDataSource.listSimContacts(ctx)
        val projected = SimContactsDataSource.simContactToContact(listed.first { it.name == "Bob" })
        val byId = SimContactsDataSource.findBackingSimContact(ctx, projected)
        assertEquals("Bob", byId?.name)

        // a hand-made contact with a non-synthetic id falls back to name+number matching
        val manual = SimContactsDataSource.simContactToContact(
            SimContact(12345, "Aisha Rahman", "+14155550102", "aisha@x.com", null)
        ).copy(id = 777)
        val byFallback = SimContactsDataSource.findBackingSimContact(ctx, manual)
        assertEquals("+14155550102", byFallback?.number)

        val nonSim = manual.copy(accountType = "com.google")
        assertNull(SimContactsDataSource.findBackingSimContact(ctx, nonSim))
    }

    @Test
    fun insertSimContactWritesTagAndNumber() {
        register()
        assertTrue(SimContactsDataSource.insertSimContact(ctx, "New Person", "+14155550199", "new@x.com", subscriptionId = 5))
        assertEquals("content://icc/adn/subId/5", FakeIccProvider.lastInsertUri.toString())
        assertEquals("New Person", FakeIccProvider.lastInsertValues?.getAsString("tag"))
        assertEquals("+14155550199", FakeIccProvider.lastInsertValues?.getAsString("number"))
        assertEquals("new@x.com", FakeIccProvider.lastInsertValues?.getAsString("emails"))

        // blank name and number never reach the provider
        val before = FakeIccProvider.lastInsertUri
        assertFalse(SimContactsDataSource.insertSimContact(ctx, " ", "", subscriptionId = 5))
        assertEquals(before, FakeIccProvider.lastInsertUri)
    }

    @Test
    fun updateSimContactWritesNewValuesAndClearsEmail() {
        register()
        val old = SimContact(1, "Old Name", "+111", "old@x.com", 3)
        assertTrue(SimContactsDataSource.updateSimContact(ctx, old, "New Name", "+222", email = null))
        assertEquals("content://icc/adn/subId/3", FakeIccProvider.lastUpdateUri.toString())
        assertEquals("Old Name", FakeIccProvider.lastUpdateValues?.getAsString("tag"))
        assertEquals("+111", FakeIccProvider.lastUpdateValues?.getAsString("number"))
        assertEquals("New Name", FakeIccProvider.lastUpdateValues?.getAsString("newTag"))
        assertEquals("+222", FakeIccProvider.lastUpdateValues?.getAsString("newNumber"))
        assertEquals("", FakeIccProvider.lastUpdateValues?.getAsString("newEmails"))

        assertFalse(SimContactsDataSource.updateSimContact(ctx, old, "", ""))
    }

    @Test
    fun deleteSimContactDeletesFromItsSubscription() {
        register()
        val contact = SimContact(1, "Aisha Rahman", "+14155550102", "aisha@x.com", 5)
        assertTrue(SimContactsDataSource.deleteSimContact(ctx, contact))
        assertEquals("content://icc/adn/subId/5", FakeIccProvider.lastDeleteUri.toString())
    }
}
