package com.vayunmathur.contacts.data

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * Regression: Contact.save() on the insert path (id == 0) must write the
 * caller's newDetails (not the contact's stale details) and must persist
 * isFavorite as STARRED, like the update path already does.
 */
@RunWith(RobolectricTestRunner::class)
class BugcheckInsertTest {

    class CapturingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor? = null

        override fun applyBatch(
            authority: String, operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> {
            batches += operations.toList()
            return Array(operations.size) { ContentProviderResult(Uri.EMPTY) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri = Uri.EMPTY
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = 1

        override fun getType(uri: Uri): String? = null

        companion object {
            val batches = mutableListOf<ContentProviderOperation>()
        }
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun valuesOf(op: ContentProviderOperation): ContentValues? {
        val field = ContentProviderOperation::class.java.getDeclaredField("mValues")
        field.isAccessible = true
        return field.get(op) as? ContentValues
    }

    @Test
    fun insertWritesNewDetailsAndStarred() {
        CapturingProvider.batches.clear()
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, CapturingProvider()
        )
        val stale = ContactDetails.empty().copy(
            names = listOf(Name(0, "", "Stale", "", "", ""))
        )
        val fresh = ContactDetails.empty().copy(
            names = listOf(Name(0, "", "Fresh", "", "", "")),
            phoneNumbers = listOf(
                PhoneNumber(0, "+15550100", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            )
        )
        val contact = Contact(0L, null, null, true, stale)
        assertTrue(contact.save(ctx, fresh, ContactDetails.empty()))

        val ops = CapturingProvider.batches
        val rawValues = valuesOf(ops.first())
        assertEquals(1, rawValues?.getAsInteger(ContactsContract.RawContacts.STARRED))
        val written = ops.mapNotNull { valuesOf(it) }.flatMap { values ->
            listOfNotNull(
                values.getAsString(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME),
                values.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER),
            )
        }
        assertTrue("Fresh" in written, "new name must be inserted, got $written")
        assertTrue("+15550100" in written, "new phone must be inserted, got $written")
        assertTrue("Stale" !in written, "stale details must not be inserted, got $written")
    }
}
