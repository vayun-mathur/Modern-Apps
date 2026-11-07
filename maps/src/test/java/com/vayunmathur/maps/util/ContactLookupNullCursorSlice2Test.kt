package com.vayunmathur.maps.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Contacts reads when the provider answers with no cursor at all (an OEM
 * provider mid-teardown does this): every read degrades to empty/null.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(RobolectricTestRunner::class)
class ContactLookupNullCursorSlice2Test {

    private class NullCursorProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `null cursors read as empty everywhere`() {
        Robolectric.setupContentProvider(NullCursorProvider::class.java, "com.android.contacts")
        val session = Uri.parse("content://com.android.contacts/session/1")
        val row = Uri.parse("content://com.android.contacts/data/7")
        val contact = Uri.parse("content://com.android.contacts/contacts/9")
        assertEquals(emptyList(), readSessionAddresses(context, session))
        assertNull(readAddress(context, row))
        assertEquals(emptyList(), readContactAddresses(context, contact))
    }
}
