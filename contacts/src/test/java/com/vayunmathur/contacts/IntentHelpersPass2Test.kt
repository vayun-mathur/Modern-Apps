package com.vayunmathur.contacts

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Second-pass coverage for MainActivity's intent helpers. */
@RunWith(RobolectricTestRunner::class)
class IntentHelpersPass2Test {

    @Test
    fun readTypeHandlesIntNumericStringCustomAndMissing() {
        val intIntent = Intent().putExtra("k", 3)
        assertEquals(3 to null, readType(intIntent, "k"))

        val numericString = Intent().putExtra("k", "2")
        assertEquals(2 to null, readType(numericString, "k"))

        val customLabel = Intent().putExtra("k", "Cabin")
        assertEquals(PhoneKind.TYPE_CUSTOM to "Cabin", readType(customLabel, "k"))

        val styled = Intent().putExtra("k", android.text.SpannableString("4"))
        assertEquals(4 to null, readType(styled, "k"))

        assertEquals(null to null, readType(Intent(), "k"))
        assertEquals(null to null, readType(Intent().putExtra("other", 1), "k"))
    }

    @Test
    fun uriPhoneNumberExtractsTelAndLookupNumbers() {
        val tel = Intent().apply { data = Uri.parse("tel:+14155550102") }
        assertEquals("+14155550102", uriPhoneNumber(tel))

        val lookup = Intent().apply {
            data = Uri.parse("content://com.android.contacts/phone_lookup/+442071234567")
        }
        assertEquals("+442071234567", uriPhoneNumber(lookup))

        val other = Intent().apply { data = Uri.parse("content://com.android.contacts/contacts/7") }
        assertNull(uriPhoneNumber(other))
        assertNull(uriPhoneNumber(Intent()))
    }

    @Test
    fun isVcfIntentMatchesTypeAndExtension() {
        assertTrue(isVcfIntent(Intent().setType("text/x-vcard")))
        assertTrue(isVcfIntent(Intent().setType("text/vcf")))
        assertTrue(
            isVcfIntent(Intent(Intent.ACTION_VIEW, Uri.parse("content://downloads/CARD.VCF")))
        )
        assertFalse(isVcfIntent(Intent().setType("text/plain")))
        assertFalse(isVcfIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/123"))))
        assertFalse(isVcfIntent(Intent()))
        assertTrue(
            ContactsContract.AUTHORITY == "com.android.contacts"
        )
    }
}
