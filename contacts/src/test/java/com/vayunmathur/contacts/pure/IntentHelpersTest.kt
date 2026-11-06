package com.vayunmathur.contacts.pure

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.isVcfIntent
import com.vayunmathur.contacts.readType
import com.vayunmathur.contacts.uriPhoneNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric coverage for main-activity intent helpers ([isVcfIntent],
 * [readType], [uriPhoneNumber]): the VIEW/pick/edit entry points route on
 * MIME type, typed extras, and the data URI — all plain Intent reads.
 */
@RunWith(RobolectricTestRunner::class)
class IntentHelpersTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun intent(action: String = Intent.ACTION_VIEW) = Intent(action)

    // -- isVcfIntent ---------------------------------------------------------------

    @Test
    fun `vcf detected by mime or path`() {
        assertTrue2(isVcfIntent(intent().setType("text/x-vcard")))
        assertTrue2(isVcfIntent(intent().setType("text/vcard")))
        assertTrue2(
            isVcfIntent(
                intent().setDataAndType(Uri.parse("content://x/y.vcf"), "application/octet-stream"),
            ),
        )
        assertTrue2(!isVcfIntent(intent().setType("text/plain")))
        assertTrue2(!isVcfIntent(intent()))
    }

    // -- readType -------------------------------------------------------------------

    @Test
    fun `readType accepts int numeric string and custom label`() {
        assertEquals(
            2 to null,
            readType(intent().putExtra("t", 2), "t"),
        )
        assertEquals(
            2 to null,
            readType(intent().putExtra("t", "2"), "t"),
        )
        assertEquals(
            ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM to "Work",
            readType(intent().putExtra("t", "Work"), "t"),
        )
    }

    @Test
    fun `readType misses on absent or wrong-typed extras`() {
        assertEquals(null to null, readType(intent(), "t"))
        assertEquals(null to null, readType(intent().putExtra("t", 1.5), "t"))
        assertEquals(null to null, readType(Intent(), "t"))
    }

    // -- uriPhoneNumber ---------------------------------------------------------------

    @Test
    fun `tel uri yields the number`() {
        assertEquals("+1555", uriPhoneNumber(intent().setData(Uri.parse("tel:+1555"))))
    }

    @Test
    fun `phone lookup uri yields the last segment`() {
        val uri = Uri.parse("content://com.android.contacts/phone_lookup/+1555")
        assertEquals("+1555", uriPhoneNumber(intent().setData(uri)))
    }

    @Test
    fun `other uris yield null`() {
        assertNull(uriPhoneNumber(intent()))
        assertNull(uriPhoneNumber(intent().setData(Uri.parse("content://com.android.contacts/contacts/7"))))
    }

    private fun assertTrue2(v: Boolean) = kotlin.test.assertTrue(v)
}
