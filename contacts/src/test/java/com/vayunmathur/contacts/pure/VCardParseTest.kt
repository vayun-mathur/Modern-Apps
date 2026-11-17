package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.VcfUtils
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins vCard import end to end through the production parser: names,
 * phones, emails, addresses, dates, orgs, notes, nicknames, groups,
 * starred flags, folding and quoted-printable decoding.
 *
 * Calls VcfUtils.parseContacts directly (it is a plain, host-safe
 * function over an InputStream). Only valid inputs are used: the
 * parser logs malformed values, and logging needs a device.
 */
class VCardParseTest {
    private fun parse(vcf: String): List<com.vayunmathur.contacts.data.Contact> =
        VcfUtils.parseContacts(ByteArrayInputStream(vcf.toByteArray(Charsets.UTF_8)))

    private fun vcard(vararg lines: String): String = lines.joinToString("\r\n")

    @Test
    fun minimalCardParsesNameParts() {
        val contacts = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "N:Doe;John;Ann;;;", "END:VCARD"))
        assertEquals(1, contacts.size)
        assertEquals("John Ann Doe", contacts[0].name.value)
        assertFalse(contacts[0].isFavorite)
    }

    @Test
    fun fullNameOnlyFallsBackToFirstAndLastSplit() {
        val contacts = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "FN:Madonna", "END:VCARD"))
        assertEquals("Madonna", contacts[0].name.value)
        assertEquals("Madonna", contacts[0].name.firstName)
        assertEquals("", contacts[0].name.lastName)
    }

    @Test
    fun structuredNameWinsOverFullName() {
        val contacts = parse(
            vcard("BEGIN:VCARD", "VERSION:3.0", "N:Doe;John;;;", "FN:Johnny D", "END:VCARD"),
        )
        assertEquals("John Doe", contacts[0].name.value)
    }

    @Test
    fun phonesKeepTypeAndCustomLabel() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD", "VERSION:3.0", "FN:A",
                "TEL;TYPE=CELL:+15551234567",
                "TEL;TYPE=HOME,VOICE:+15557654321",
                "TEL;TYPE=X-Satellite:+881600000000",
                "END:VCARD",
            ),
        )
        val phones = contacts[0].details.phoneNumbers
        assertEquals(3, phones.size)
        assertEquals("+15551234567", phones[0].number)
        assertEquals("", phones[0].label)
        assertTrue(phones[0].type != phones[1].type, "cell and home must map to different types")
        assertEquals("Satellite", phones[2].label)
    }

    @Test
    fun emailsMapHomeWorkAndOther() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD", "VERSION:3.0", "FN:A",
                "EMAIL;TYPE=HOME:a@home.com",
                "EMAIL;TYPE=WORK:a@work.com",
                "EMAIL:a@other.com",
                "END:VCARD",
            ),
        )
        val emails = contacts[0].details.emails
        assertEquals(listOf("a@home.com", "a@work.com", "a@other.com"), emails.map { it.address })
        assertEquals(3, emails.map { it.type }.toSet().size)
    }

    @Test
    fun addressFormatsStreetCityRegionPostalCountry() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD", "VERSION:3.0", "FN:A",
                "ADR;TYPE=HOME:;;123 Main St;Springfield;IL;62701;USA",
                "END:VCARD",
            ),
        )
        assertEquals("123 Main St, Springfield, IL, 62701, USA", contacts[0].details.addresses[0].value)
    }

    @Test
    fun birthdayAcceptsNumericDateAndFirstWins() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD", "VERSION:3.0", "FN:A",
                "BDAY:20200115",
                "BDAY:19991231",
                "END:VCARD",
            ),
        )
        assertEquals(1, contacts[0].details.dates.size)
        assertEquals("2020-01-15", contacts[0].details.dates[0].value)
        assertEquals("2020-01-15", contacts[0].birthday?.value)
    }

    @Test
    fun noYearBirthdayUses1604Sentinel() {
        val contacts = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "BDAY:--05-04", "END:VCARD"))
        assertEquals("1604-05-04", contacts[0].details.dates[0].value)
    }

    @Test
    fun customEventKeepsLabel() {
        val contacts = parse(
            vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "X-EVENT;TYPE=X-Party:2020-05-04", "END:VCARD"),
        )
        assertEquals(1, contacts[0].details.dates.size)
        assertEquals("Party", contacts[0].details.dates[0].label)
    }

    @Test
    fun orgNoteNicknameCategoriesAndStarred() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD", "VERSION:3.0", "FN:A",
                "ORG:Acme Inc",
                "NOTE:first\\nsecond",
                "NICKNAME:Al,Bobby",
                "CATEGORIES:Friends",
                "X-STARRED:1",
                "END:VCARD",
            ),
        )
        val details = contacts[0].details
        assertEquals("Acme Inc", contacts[0].org.company)
        assertEquals("first\nsecond", contacts[0].note.content)
        assertEquals(listOf("Al", "Bobby"), details.nicknames.map { it.nickname })
        assertTrue(contacts[0].isFavorite)
    }

    @Test
    fun urlLinesNeverBecomeNotes() {
        val contacts = parse(
            vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "URL:https://example.com", "END:VCARD"),
        )
        assertTrue(contacts[0].details.notes.all { it.content.isBlank() })
    }

    @Test
    fun foldedNoteLineUnfoldsBeforeUnescaping() {
        val contacts = parse(
            vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "NOTE:abc", " def\\,ghi", "END:VCARD"),
        )
        assertEquals("abcdef,ghi", contacts[0].note.content)
    }

    @Test
    fun quotedPrintableNameDecodesUtf8() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD",
                "VERSION:3.0",
                "N;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Doe;J=C3=B6rg;;;",
                "END:VCARD",
            ),
        )
        assertEquals("Jörg Doe", contacts[0].name.value)
    }

    @Test
    fun photoLineIsStoredVerbatim() {
        val contacts = parse(
            vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "PHOTO;ENCODING=b:aGVsbG8=", "END:VCARD"),
        )
        assertEquals("aGVsbG8=", contacts[0].photo?.photo)
    }

    @Test
    fun strayLinesOutsideCardsAreIgnored() {
        val contacts = parse(
            vcard(
                "garbage line",
                "FN:No Card Here",
                "BEGIN:VCARD", "VERSION:3.0", "FN:Kept",
                "not:a:valid:colonless",
                "END:VCARD",
                "trailing garbage",
            ),
        )
        assertEquals(1, contacts.size)
        assertEquals("Kept", contacts[0].name.value)
    }

    @Test
    fun twoCardsParseInOrder() {
        val contacts = parse(
            vcard(
                "BEGIN:VCARD", "VERSION:3.0", "FN:First", "END:VCARD",
                "BEGIN:VCARD", "VERSION:3.0", "FN:Second", "END:VCARD",
            ),
        )
        assertEquals(listOf("First", "Second"), contacts.map { it.name.value })
    }

    @Test
    fun starredAcceptsYesVariantsAndRejectsZero() {
        val yes = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "FN:A", "X-STARRED:yes", "END:VCARD"))
        val no = parse(vcard("BEGIN:VCARD", "VERSION:3.0", "FN:B", "X-STARRED:0", "END:VCARD"))
        assertTrue(yes[0].isFavorite)
        assertFalse(no[0].isFavorite)
    }

    @Test
    fun categoriesReachTheGroupResolver() {
        val contacts = VcfUtils.parseContacts(
            ByteArrayInputStream(
                vcard(
                    "BEGIN:VCARD", "VERSION:3.0", "FN:A", "CATEGORIES:Friends,Family", "END:VCARD",
                ).toByteArray(Charsets.UTF_8),
            ),
        ) { names ->
            names.mapIndexed { index, _ -> com.vayunmathur.contacts.data.GroupMembership(0, index + 1L) }
        }
        assertEquals(listOf(1L, 2L), contacts[0].details.groups.map { it.groupId })
    }
}
