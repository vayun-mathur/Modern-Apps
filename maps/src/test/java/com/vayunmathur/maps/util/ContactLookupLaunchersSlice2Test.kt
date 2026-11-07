package com.vayunmathur.maps.util

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The picker fallback chain with fake launchers (postal refused → contact
 * picker → give up), and session reads over a cursor that carries no
 * mimetype column at all.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(RobolectricTestRunner::class)
class ContactLookupLaunchersSlice2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeLauncher<I>(
        override val contract: ActivityResultContract<I, *>,
        private val fails: Boolean = false,
    ) : ActivityResultLauncher<I>() {
        val launchedInputs = mutableListOf<I?>()
        override fun launch(input: I, options: ActivityOptionsCompat?) {
            launchedInputs += input
            if (fails) throw ActivityNotFoundException("no handler")
        }
        override fun unregister() {}
    }

    /** Serves postal rows with NO mimetype column in the cursor. */
    private class NoMimeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            val cursor = MatrixCursor(
                arrayOf(
                    StructuredPostal.FORMATTED_ADDRESS,
                    StructuredPostal.STREET,
                    StructuredPostal.CITY,
                    StructuredPostal.REGION,
                    StructuredPostal.POSTCODE,
                    StructuredPostal.COUNTRY,
                ),
            )
            cursor.addRow(arrayOf<Any?>("9 Elm St\nSpringfield", null, null, null, null, null))
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    @Test fun `the postal picker launches a postal pick intent`() {
        val postal = FakeLauncher(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult())
        val contact = FakeLauncher(androidx.activity.result.contract.ActivityResultContracts.PickContact())
        var noPicker = 0
        launchPostalPicker(postal, contact) { noPicker++ }
        assertEquals(1, postal.launchedInputs.size)
        assertEquals(StructuredPostal.CONTENT_TYPE, postal.launchedInputs.first()?.type)
        assertEquals(0, contact.launchedInputs.size)
        assertEquals(0, noPicker)
    }

    @Test fun `a refused postal picker falls back to the contact picker`() {
        val postal = FakeLauncher(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            fails = true,
        )
        val contact = FakeLauncher(androidx.activity.result.contract.ActivityResultContracts.PickContact())
        var noPicker = 0
        launchPostalPicker(postal, contact) { noPicker++ }
        assertEquals(1, contact.launchedInputs.size)
        assertNull(contact.launchedInputs.first())
        assertEquals(0, noPicker)
    }

    @Test fun `when even the contact picker is refused the caller is told`() {
        val postal = FakeLauncher(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            fails = true,
        )
        val contact = FakeLauncher(
            androidx.activity.result.contract.ActivityResultContracts.PickContact(),
            fails = true,
        )
        var noPicker = 0
        launchPostalPicker(postal, contact) { noPicker++ }
        assertEquals(1, noPicker)
    }

    @Test fun `the contact picker alone reports refusal the same way`() {
        val contact = FakeLauncher(
            androidx.activity.result.contract.ActivityResultContracts.PickContact(),
            fails = true,
        )
        var noPicker = 0
        launchContactPicker(contact) { noPicker++ }
        assertEquals(1, noPicker)
    }

    @Test fun `a session cursor without a mimetype column still reads its rows`() {
        Robolectric.setupContentProvider(NoMimeProvider::class.java, "com.android.contacts")
        val uri = Uri.parse("content://com.android.contacts/session/42")
        assertEquals(
            listOf("9 Elm St, Springfield"),
            readSessionAddresses(context, uri),
        )
    }

    @Test fun `a contact uri that no longer resolves reads empty`() {
        Robolectric.setupContentProvider(
            ContactLookupProvidersSlice2Test.FakeContactsProvider::class.java,
            "com.android.contacts",
        )
        val uri = Uri.parse("content://com.android.contacts/contacts/404")
        assertEquals(emptyList(), readContactAddresses(context, uri))
    }
}
