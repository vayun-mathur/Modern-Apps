package com.vayunmathur.contacts.pure

import android.provider.ContactsContract.CommonDataKinds.Email as EmailKind
import android.provider.ContactsContract.CommonDataKinds.Event as EventKind
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal as PostalKind
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.VcfUtils
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: vCard export covers every field writer, folding, escaping and type tokens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VcfExportW5Test {

    private fun export(contacts: List<Contact>, groups: Map<Long, String> = emptyMap()): String =
        runBlocking {
            val out = ByteArrayOutputStream()
            VcfUtils.exportContacts(contacts, out, groups)
            out.toString(Charsets.UTF_8)
        }

    private fun richContact(): Contact {
        val longNote = "Line one, with punctuation; and a backslash \\ plus ".repeat(4) +
            "a tail long enough to force line folding in the exported card"
        val details = ContactDetails.empty().copy(
            names = listOf(Name(1, "Dr.", "Jürgen", "Middle;Name", "Example, Jr.", "III")),
            phoneNumbers = listOf(
                PhoneNumber(1, "+1 555 0100", PhoneKind.TYPE_MOBILE),
                PhoneNumber(2, "+1 555 0101", PhoneKind.TYPE_HOME),
                PhoneNumber(3, "+1 555 0102", PhoneKind.TYPE_WORK),
                PhoneNumber(4, "+1 555 0103", PhoneKind.TYPE_FAX_WORK),
                PhoneNumber(5, "+1 555 0104", PhoneKind.TYPE_FAX_HOME),
                PhoneNumber(6, "+1 555 0105", PhoneKind.TYPE_CUSTOM, "Boat, Phone;Cell:1"),
                PhoneNumber(7, "+1 555 0106", 99),
            ),
            emails = listOf(
                Email(1, "home@example.com", EmailKind.TYPE_HOME),
                Email(2, "work@example.com", EmailKind.TYPE_WORK),
                Email(3, "club@example.com", EmailKind.TYPE_CUSTOM, "Club;Night"),
                Email(4, "other@example.com", EmailKind.TYPE_OTHER),
            ),
            addresses = listOf(
                Address(1, "1200 Market St, San Francisco; CA", PostalKind.TYPE_HOME),
                Address(2, "1 Work Plaza", PostalKind.TYPE_WORK),
                Address(3, "PO Box 9", PostalKind.TYPE_CUSTOM, "Cabin"),
            ),
            dates = listOf(
                Event(1, LocalDate(1990, 3, 15), EventKind.TYPE_BIRTHDAY),
                Event(2, LocalDate(2015, 6, 20), EventKind.TYPE_ANNIVERSARY),
                Event(3, LocalDate(2020, 1, 2), EventKind.TYPE_CUSTOM, "Met;Day"),
                Event(4, LocalDate(1604, 2, 29), EventKind.TYPE_OTHER),
            ),
            orgs = listOf(Organization(1, "Example, Inc.; Research")),
            notes = listOf(Note(1, longNote), Note(2, "")),
            nicknames = listOf(Nickname(1, "JJ", 1), Nickname(2, "", 1)),
            groups = listOf(GroupMembership(1, 11), GroupMembership(2, 12), GroupMembership(3, 13)),
            genders = listOf(Gender(1, "o", "nonbinary; self-described"), Gender(2, "")),
            commPrefs = listOf(CommPref(1, "+1 555 0100", CommPlatform.WHATSAPP)),
            photos = listOf(Photo(1, "QUJDREVGRw==")),
        )
        return Contact(
            id = 42,
            accountType = "com.google",
            accountName = "a@gmail.com",
            isFavorite = true,
            details = details,
        )
    }

    @Test
    fun exportWritesEveryFieldWithEscapesAndTokens() {
        val text = export(listOf(richContact()), mapOf(11L to "Family", 12L to ""))
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"), text.take(100))
        assertTrue(text.contains("N:Example\\, Jr.;Jürgen;Middle\\;Name;Dr.;III"))
        assertTrue(text.contains("TEL;TYPE=CELL:+1 555 0100"))
        assertTrue(text.contains("TEL;TYPE=FAX:+1 555 0103"))
        assertTrue(text.contains("TEL;TYPE=X-Boat PhoneCell1:+1 555 0105"), text)
        assertTrue(text.contains("TEL;TYPE=VOICE:+1 555 0106"))
        assertTrue(text.contains("EMAIL;TYPE=X-ClubNight:club@example.com"), text)
        assertTrue(text.contains("EMAIL;TYPE=INTERNET:other@example.com"))
        assertTrue(text.contains("ADR;TYPE=WORK:;;1 Work Plaza;;;;"))
        assertTrue(text.contains("ADR;TYPE=X-Cabin:;;PO Box 9;;;;"))
        assertTrue(text.contains("BDAY:1990-03-15"))
        assertTrue(text.contains("X-EVENT;TYPE=OTHER:2015-06-20"))
        assertTrue(text.contains("X-EVENT;TYPE=X-MetDay:2020-01-02"), text)
        assertTrue(text.contains("ORG:Example\\, Inc.\\; Research"))
        assertTrue(text.contains("NICKNAME:JJ"))
        assertTrue(text.contains("GENDER:O;nonbinary\\; self-described"), text)
        assertTrue(text.contains("X-COMM-PREF:whatsapp;+1 555 0100"))
        assertTrue(text.contains("CATEGORIES:Family,12,13"), text)
        assertTrue(text.contains("X-STARRED:1"))
        assertTrue(text.contains("PHOTO;ENCODING=b:QUJDREVGRw=="))
        assertTrue(text.trimEnd().endsWith("END:VCARD"))
        // Folding: no physical line exceeds 76 chars (75 + continuation space)
        // and the long note survives across continuation lines when unfolded.
        assertTrue(text.lines().all { it.length <= 76 }, text.lines().maxOf { it.length }.toString())
        val unfolded = text.replace("\r\n ", "")
        assertTrue(unfolded.contains("Line one\\, with punctuation\\; and a backslash \\\\ plus"))
    }

    @Test
    fun exportOmitsOptionalLinesForSparseContactAndUsesUnspecifiedGender() {
        val sparse = Contact(
            id = 7,
            accountType = null,
            accountName = null,
            isFavorite = false,
            details = ContactDetails.empty().copy(
                genders = listOf(Gender(1, "not-a-code", "ignored text")),
                commPrefs = listOf(CommPref(1, "", CommPlatform.SIM)),
            ),
        )
        val text = export(listOf(sparse))
        assertTrue(text.contains("N:;;;;"))
        assertTrue(text.contains("GENDER:U"), text)
        assertFalse(text.contains("ORG:"))
        assertFalse(text.contains("BDAY:"))
        assertFalse(text.contains("CATEGORIES:"))
        assertFalse(text.contains("X-COMM-PREF:"))
        assertFalse(text.contains("X-STARRED"))
        assertFalse(text.contains("PHOTO"))
        assertEquals(1, text.split("BEGIN:VCARD").size - 1)
    }

    @Test
    fun exportedCardRoundTripsThroughParser() {
        val source = richContact()
        val text = export(listOf(source), mapOf(11L to "Family", 13L to "Crew"))
        val parsed = VcfUtils.parseContacts(text.byteInputStream())
        assertEquals(1, parsed.size)
        val contact = parsed.single()
        assertEquals("Jürgen", contact.name.firstName)
        assertTrue(contact.details.phoneNumbers.any { it.number == "+1 555 0100" })
        assertTrue(contact.details.emails.any { it.address == "work@example.com" })
        assertTrue(contact.details.dates.any { it.startDate == LocalDate(1990, 3, 15) })
        assertTrue(contact.isFavorite)
    }
}
