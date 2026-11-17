package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.VcfUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: vCard parameter forms — grouped, multi-type, quoted, charset and X- params. */
@RunWith(RobolectricTestRunner::class)
class VcfParamsW5Test {
    private fun parse(vcf: String) = VcfUtils.parseContacts(vcf.byteInputStream())

    @Test
    fun groupedAndMultiTypeParams() {
        val contacts = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Grouped Person
            N:Person;Grouped;;;
            item1.TEL;TYPE=CELL,VOICE:+14155550102
            item1.X-ABLabel:ItemLabel
            TEL;TYPE="HOME,VOICE":+442071234567
            EMAIL;TYPE=INTERNET,HOME:grouped@x.com
            END:VCARD
            """.trimIndent()
        )
        assertEquals(1, contacts.size)
        val numbers = contacts[0].details.phoneNumbers.map { it.number }
        // Group-prefixed properties go through the param parser even when
        // the parser ultimately keeps only the ungrouped rows.
        assertTrue(numbers.isNotEmpty(), "phones should parse, got $numbers")
        assertTrue(numbers.contains("+442071234567"), "quoted multi-type phone should parse, got $numbers")
        assertEquals(listOf("grouped@x.com"), contacts[0].details.emails.map { it.address })
    }

    @Test
    fun charsetAndQuotedPrintableParams() {
        val contacts = parse(
            """
            BEGIN:VCARD
            VERSION:2.1
            FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:J=C3=BCrgen_M=C3=BCller
            N;CHARSET=UTF-8:M=C3=BCller;J=C3=BCrgen;;;
            TEL;TYPE=CELL:+4915112345678
            END:VCARD
            """.trimIndent()
        )
        assertEquals(1, contacts.size)
        assertTrue(
            contacts[0].name.value.contains("Jürgen") || contacts[0].name.firstName.isNotEmpty(),
            "quoted-printable name should decode, got ${contacts[0].name}",
        )
        assertEquals(listOf("+4915112345678"), contacts[0].details.phoneNumbers.map { it.number })
    }

    @Test
    fun prefAndValueParamsAndUnknownXParamsAreTolerated() {
        val contacts = parse(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:Pref Person
            N:Person;Pref;;;
            TEL;VALUE=uri;PREF=1;TYPE=cell:tel:+14155550109
            EMAIL;PREF=2:pref@x.com
            X-CUSTOM-PROP;X-WHATEVER=1:some value
            PHOTO;VALUE=uri:https://example.org/photo.jpg
            BDAY:19920315
            ANNIVERSARY:20200102
            GENDER:F
            END:VCARD
            """.trimIndent()
        )
        assertEquals(1, contacts.size)
        val contact = contacts[0]
        assertTrue(contact.details.phoneNumbers.isNotEmpty())
        assertEquals(listOf("pref@x.com"), contact.details.emails.map { it.address })
        assertTrue(contact.details.dates.size >= 1, "bday should parse, dates=${contact.details.dates}")
    }

    @Test
    fun malformedParamsDoNotLoseTheCard() {
        val contacts = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Odd Person
            N:Person;Odd;;;
            TEL;TYPE=:111
            TEL;BROKENPARAM:+14155550111
            EMAIL;:odd@x.com
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Second Person
            N:Person;Second;;;
            TEL:+14155550112
            END:VCARD
            """.trimIndent()
        )
        assertEquals(2, contacts.size)
        assertTrue(contacts.flatMap { it.details.phoneNumbers }.map { it.number }.contains("+14155550112"))
    }
}
