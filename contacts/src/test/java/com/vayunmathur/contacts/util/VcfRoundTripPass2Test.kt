package com.vayunmathur.contacts.util

import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Export → parse round-trips for VcfUtils, including escaping and folding. */
class VcfRoundTripPass2Test {

    private fun richContact(): Contact = Contact(
        id = 7, accountType = "com.google", accountName = "a@gmail.com", isFavorite = true,
        details = ContactDetails(
            phoneNumbers = listOf(
                PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(2, "+442071234567", CDKPhone.TYPE_CUSTOM, "Cabin, Lake; House"),
            ),
            emails = listOf(
                Email(3, "aisha@x.com", CDKEmail.TYPE_HOME),
                Email(4, "aisha@work.com", CDKEmail.TYPE_WORK),
            ),
            addresses = listOf(
                Address(5, "1200 Market St, Springfield", CDKStructuredPostal.TYPE_HOME),
            ),
            dates = listOf(
                Event(6, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY),
            ),
            photos = listOf(Photo(7, "QUJD")),
            names = listOf(Name(8, "Dr", "Aisha", "M", "Rahman", "Jr")),
            orgs = listOf(Organization(9, "Acme, Inc.")),
            notes = listOf(Note(10, "Line one\nLine two, with; punctuation \\ and more")),
            nicknames = listOf(Nickname(11, "Ash", CDKNickname.TYPE_DEFAULT)),
            groups = listOf(GroupMembership(12, 11L), GroupMembership(13, 12L)),
            genders = listOf(Gender(14, Gender.FEMALE)),
            commPrefs = listOf(CommPref(15, "+14155550102", CommPlatform.WHATSAPP)),
        ),
        customRingtone = "content://media/ringtone/1",
    )

    private fun export(vararg contacts: Contact, groups: Map<Long, String> = emptyMap()): String {
        val out = ByteArrayOutputStream()
        runBlocking { VcfUtils.exportContacts(contacts.toList(), out, groups) }
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun exportWritesAllSections() {
        val vcf = export(richContact(), groups = mapOf(11L to "Family", 12L to "Friends"))
        assertTrue(vcf.startsWith("BEGIN:VCARD"), vcf)
        assertTrue(vcf.contains("VERSION:3.0"))
        assertTrue(vcf.contains("N:Rahman;Aisha;M;Dr;Jr"), vcf)
        assertTrue(vcf.contains("TEL;TYPE=CELL:+14155550102"), vcf)
        assertTrue(vcf.contains("aisha@x.com"))
        assertTrue(vcf.contains("ORG:Acme\\, Inc."), vcf)
        assertTrue(vcf.contains("X-EVENT;TYPE=OTHER:2020-06-01"), vcf)
        assertTrue(vcf.contains("GENDER:F"), vcf)
        assertTrue(vcf.contains("X-COMM-PREF:whatsapp;+14155550102"), vcf)
        assertTrue(vcf.contains("X-STARRED:1"))
        assertTrue(vcf.contains("Family"), vcf)
        assertTrue(vcf.trimEnd().endsWith("END:VCARD"))
        // long lines fold at 75 columns with a leading-space continuation
        assertTrue(vcf.lines().all { it.length <= 76 }, vcf.lines().maxOf { it.length }.toString())
    }

    @Test
    fun roundTripPreservesCoreFields() {
        val original = richContact()
        val vcf = export(original, groups = mapOf(11L to "Family", 12L to "Friends"))
        val parsed = VcfUtils.parseContacts(ByteArrayInputStream(vcf.toByteArray())) { names ->
            names.mapIndexed { index, _ -> GroupMembership(0, 100L + index) }
        }
        assertEquals(1, parsed.size)
        val contact = parsed.single()
        assertEquals("Aisha", contact.name.firstName)
        assertEquals("Rahman", contact.name.lastName)
        assertTrue(contact.details.phoneNumbers.any { it.number == "+14155550102" })
        assertTrue(contact.details.emails.any { it.address == "aisha@x.com" })
        assertEquals("Acme, Inc.", contact.org.company)
        assertTrue(contact.isFavorite)
    }

    @Test
    fun exportEmptyListWritesNothing() {
        assertEquals("", export())
    }

    @Test
    fun exportContactWithoutDetailsStillWritesCard() {
        val bare = Contact(
            id = 1, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails.empty(),
        )
        val vcf = export(bare)
        assertTrue(vcf.contains("BEGIN:VCARD"))
        assertTrue(vcf.contains("N:;;;;"))
        val parsed = VcfUtils.parseContacts(ByteArrayInputStream(vcf.toByteArray()))
        assertEquals(1, parsed.size)
    }

    @Test
    fun parseMalformedAndEmptyInputs() {
        assertEquals(emptyList(), VcfUtils.parseContacts(ByteArrayInputStream(ByteArray(0))))
        assertEquals(
            emptyList(),
            VcfUtils.parseContacts(ByteArrayInputStream("not a vcard\nat all".toByteArray())),
        )
        val unclosed = "BEGIN:VCARD\nFN:No End\n"
        assertEquals(emptyList(), VcfUtils.parseContacts(ByteArrayInputStream(unclosed.toByteArray())))
    }

    @Test
    fun parseHandlesCrlfAndFoldedLines() {
        val vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Folded\r\n Name\r\nN:Name;Folded;;;\r\n" +
            "TEL;TYPE=CELL:+14155550102\r\nEND:VCARD\r\n"
        val parsed = VcfUtils.parseContacts(ByteArrayInputStream(vcf.toByteArray()))
        assertEquals(1, parsed.size)
        assertEquals("Folded", parsed.single().name.firstName)
        assertEquals("+14155550102", parsed.single().details.phoneNumbers.single().number)
    }
}
