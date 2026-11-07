package com.vayunmathur.maps.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Postal-row composition edges: a null mimetype counts as postal, a blank
 * formatted address falls back to components, and whitespace-only rows
 * read as no address.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(RobolectricTestRunner::class)
class ContactLookupPostalSlice2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessionUri = Uri.parse("content://com.android.contacts/session/3")
    private val rowUri = Uri.parse("content://com.android.contacts/data/11")

    private fun setUp(rows: Map<String, List<Array<Any?>>>) {
        val provider = Robolectric.setupContentProvider(
            ContactLookupProvidersSlice2Test.FakeContactsProvider::class.java,
            "com.android.contacts",
        )
        provider.tables = rows
    }

    private fun row(
        formatted: String? = null,
        street: String? = null,
        city: String? = null,
        region: String? = null,
        postcode: String? = null,
        country: String? = null,
        mime: String? = StructuredPostal.CONTENT_ITEM_TYPE,
    ): Array<Any?> = arrayOf(mime, formatted, street, city, region, postcode, country, "9")

    @Test fun `a session row with a null mimetype counts as postal`() {
        setUp(mapOf(sessionUri.toString() to listOf(row(formatted = "1 Main St", mime = null))))
        assertEquals(listOf("1 Main St"), readSessionAddresses(context, sessionUri))
    }

    @Test fun `a blank formatted address falls back to its components`() {
        setUp(
            mapOf(
                rowUri.toString() to listOf(
                    row(formatted = "   ", street = "2 Oak Ave", city = "Springfield"),
                ),
            ),
        )
        assertEquals("2 Oak Ave, Springfield", readAddress(context, rowUri))
    }

    @Test fun `a whitespace only address reads as no address`() {
        setUp(mapOf(rowUri.toString() to listOf(row(formatted = "\n  \n"))))
        assertNull(readAddress(context, rowUri))
    }

    @Test fun `a multiline formatted address collapses blank lines and runs of spaces`() {
        setUp(mapOf(rowUri.toString() to listOf(row(formatted = "3  Pine   St\n\nSpringfield,  IL"))))
        assertEquals("3 Pine St, Springfield, IL", readAddress(context, rowUri))
    }
}
