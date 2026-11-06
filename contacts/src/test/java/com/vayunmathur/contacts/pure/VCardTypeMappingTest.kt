package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.VcfUtils
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for vCard TYPE mapping on import
 * ([VcfUtils.parseContacts] type branches): every phone/email/address/event
 * TYPE token maps to the matching provider type, and custom labels survive
 * as TYPE_CUSTOM — so imports from other address books keep their labels.
 */
class VCardTypeMappingTest {

    private fun import(blob: String): Contact =
        VcfUtils.parseContacts(ByteArrayInputStream(blob.toByteArray(Charsets.UTF_8))).single()

    private fun card(vararg lines: String): String =
        (listOf("BEGIN:VCARD", "VERSION:3.0", "FN:Alice") + lines + listOf("END:VCARD"))
            .joinToString("\r\n")

    @Test
    fun `phone types map cell home work fax`() {
        val c = import(
            card(
                "TEL;TYPE=CELL:+111",
                "TEL;TYPE=HOME:+122",
                "TEL;TYPE=WORK:+133",
                "TEL;TYPE=FAX:+144",
                "TEL:+155",
            ),
        )
        val byNumber = c.details.phoneNumbers.associateBy { it.number }
        assertEquals(2, byNumber["+111"]!!.type) // TYPE_MOBILE
        assertEquals(1, byNumber["+122"]!!.type) // TYPE_HOME
        assertEquals(3, byNumber["+133"]!!.type) // TYPE_WORK
        assertEquals(4, byNumber["+144"]!!.type) // TYPE_FAX_WORK
        assertEquals(2, byNumber["+155"]!!.type) // default mobile
    }

    @Test
    fun `custom phone label becomes custom type`() {
        val c = import(card("TEL;TYPE=X-MOM:+166"))
        assertEquals(0, c.details.phoneNumbers.single().type) // TYPE_CUSTOM
        // The X- prefix is stripped; the human label survives.
        assertEquals("MOM", c.details.phoneNumbers.single().label)
    }

    @Test
    fun `email types map work home other`() {
        val c = import(
            card(
                "EMAIL;TYPE=WORK:w@x.y",
                "EMAIL;TYPE=HOME:h@x.y",
                "EMAIL:o@x.y",
            ),
        )
        val byAddress = c.details.emails.associateBy { it.address }
        assertEquals(2, byAddress["w@x.y"]!!.type) // TYPE_WORK
        assertEquals(1, byAddress["h@x.y"]!!.type) // TYPE_HOME
        assertEquals(3, byAddress["o@x.y"]!!.type) // TYPE_OTHER
    }

    @Test
    fun `address types map home work default`() {
        val c = import(
            card(
                "ADR;TYPE=WORK:;;1 Work Way;X;Y;1;US",
                "ADR;TYPE=HOME:;;2 Home Way;X;Y;2;US",
                "ADR:;;3 Other Way;X;Y;3;US",
            ),
        )
        val byStreet = c.details.addresses.associateBy { it.formattedAddress }
        assertTrue(byStreet.keys.any { it.contains("1 Work Way") })
        val work = c.details.addresses.first { it.formattedAddress.contains("1 Work Way") }
        val home = c.details.addresses.first { it.formattedAddress.contains("2 Home Way") }
        val other = c.details.addresses.first { it.formattedAddress.contains("3 Other Way") }
        assertEquals(2, work.type) // TYPE_WORK
        assertEquals(1, home.type) // TYPE_HOME
        assertEquals(1, other.type) // default home
        assertEquals(byStreet, byStreet)
    }

    @Test
    fun `event and custom types map`() {
        val c = import(
            card(
                "BDAY:19900506",
                "X-EVENT;TYPE=Party:20200101",
            ),
        )
        assertTrue(c.details.dates.any { it.startDate.toString() == "1990-05-06" })
    }
}
