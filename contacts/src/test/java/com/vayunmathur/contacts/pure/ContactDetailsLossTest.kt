package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.Address
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
import com.vayunmathur.contacts.util.SimDroppedField
import com.vayunmathur.contacts.util.computeSimLoss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * Pure-JVM coverage for the contact detail model ([ContactDetails], detail
 * `with*` copies) and the SIM-move loss computation ([computeSimLoss]).
 *
 * No provider, no context: every `with*` copy shape and every loss rule is
 * pinned so a field added to the model cannot silently escape the SIM
 * warn-before-truncate dialog.
 */
class ContactDetailsLossTest {

    private fun details(
        phones: List<PhoneNumber> = emptyList(),
        emails: List<Email> = emptyList(),
        addresses: List<Address> = emptyList(),
        dates: List<Event> = emptyList(),
        photos: List<Photo> = emptyList(),
        names: List<Name> = emptyList(),
        orgs: List<Organization> = emptyList(),
        notes: List<Note> = emptyList(),
        nicknames: List<Nickname> = emptyList(),
        groups: List<GroupMembership> = emptyList(),
        genders: List<Gender> = emptyList(),
        commPrefs: List<CommPref> = emptyList(),
    ) = ContactDetails(phones, emails, addresses, dates, photos, names, orgs, notes, nicknames, groups, genders, commPrefs)

    private fun contact(
        details: ContactDetails = ContactDetails.empty(),
        favorite: Boolean = false,
        ringtone: String? = null,
    ) = Contact(
        id = 1L,
        accountType = "com.google",
        accountName = "a",
        isFavorite = favorite,
        details = details,
        customRingtone = ringtone,
    )

    private fun phone(id: Long = 1L, number: String = "+1555", type: Int = 2) = PhoneNumber(id, number, type)
    private fun email(id: Long = 1L, address: String = "a@b.c", type: Int = 1) = Email(id, address, type)

    // -- ContactDetails aggregation ------------------------------------------------

    @Test
    fun `all concatenates every detail list in order`() {
        val d = details(
            phones = listOf(phone()),
            emails = listOf(email()),
            addresses = listOf(Address(1, "123 Main", 1)),
            dates = listOf(Event(1, LocalDate(1990, 5, 6), 3)),
            photos = listOf(Photo(1, "blob")),
            names = listOf(Name(1, "", "Ada", "", "L", "")),
            orgs = listOf(Organization(1, "Acme")),
            notes = listOf(Note(1, "n")),
            nicknames = listOf(Nickname(1, "Al", 1)),
            groups = listOf(GroupMembership(1, 7L)),
            genders = listOf(Gender(1, Gender.FEMALE)),
        )
        val all = d.all()
        assertEquals(11, all.size)
        assertEquals("+1555", all[0].value)
        assertEquals("a@b.c", all[1].value)
        assertEquals("123 Main", all[2].value)
        assertEquals("1990-05-06", all[3].value)
        assertEquals("blob", all[4].value)
        assertEquals("Ada L", all[5].value)
        assertEquals("Acme", all[6].value)
        assertEquals("n", all[7].value)
        assertEquals("Al", all[8].value)
        assertEquals("7", all[9].value)
        assertEquals(Gender.FEMALE, all[10].value)
    }

    @Test
    fun `empty details aggregate to nothing and commPref is null`() {
        val d = ContactDetails.empty()
        assertTrue(d.all().isEmpty())
        assertEquals(null, d.commPref)
    }

    @Test
    fun `commPref is the first comm pref`() {
        val a = CommPref(id = 1L, number = "+1555", platform = com.vayunmathur.contacts.data.CommPlatform.WHATSAPP)
        val b = CommPref(id = 2L, number = "+1666", platform = com.vayunmathur.contacts.data.CommPlatform.SIGNAL)
        assertEquals(a, details(commPrefs = listOf(a, b)).commPref)
    }

    @Test
    fun `commpref withValue parses platform and number`() {
        val parsed = CommPref(1, "", com.vayunmathur.contacts.data.CommPlatform.SIM).withValue("signal;+1555")
        assertEquals("+1555", parsed.number)
        assertEquals(com.vayunmathur.contacts.data.CommPlatform.SIGNAL, parsed.platform)
        assertEquals("signal;+1555", parsed.value)
        // Unknown keys fall back to SIM.
        assertEquals(
            com.vayunmathur.contacts.data.CommPlatform.SIM,
            CommPref(1, "").withValue("nope;+1").platform,
        )
    }

    // -- with* copies ----------------------------------------------------------------

    @Test
    fun `phone email and address copies replace one field`() {
        assertEquals("+1999", phone().withValue("+1999").number)
        assertEquals(9, phone().withType(9).type)
        assertEquals("Work", phone().withLabel("Work").label)
        assertEquals("b@x.y", email().withValue("b@x.y").address)
        val addr = Address(3, "Old", 1, "Lbl")
        assertEquals("New", addr.withValue("New").formattedAddress)
        assertEquals(2, addr.withType(2).type)
        assertEquals("L2", addr.withLabel("L2").label)
    }

    @Test
    fun `event copies reparse the date`() {
        val e = Event(1, LocalDate(2000, 1, 2), 3, "BD")
        assertEquals(LocalDate(2010, 12, 31), e.withValue("2010-12-31").startDate)
        assertEquals(1, e.withType(1).type)
        assertEquals("X", e.withLabel("X").label)
    }

    @Test
    fun `gender withValue splits code and text`() {
        assertEquals(Gender(1, "O", "X"), Gender(1, "U").withValue("O;X"))
        assertEquals("O;X", Gender(1, "O", "X").value)
        assertEquals("F", Gender(1, "F").value)
    }

    @Test
    fun `group membership withValue parses the id`() {
        assertEquals(42L, GroupMembership(1, 7L).withValue("42").groupId)
    }

    @Test
    fun `immutable details throw on unsupported copies`() {
        assertFailsWith<UnsupportedOperationException> { Photo(1, "b").withType(2) }
        assertFailsWith<UnsupportedOperationException> { Organization(1, "c").withType(2) }
        assertFailsWith<UnsupportedOperationException> { Name(1, "", "A", "", "B", "").withValue("x") }
        assertFailsWith<UnsupportedOperationException> { Name(1, "", "A", "", "B", "").withType(1) }
        assertFailsWith<UnsupportedOperationException> { Note(1, "n").withType(2) }
        assertEquals(Note(1, "n"), Note(1, "n").withLabel("ignored"))
        assertEquals(Nickname(1, "n", 1), Nickname(1, "n", 1).withLabel("ignored"))
        assertFailsWith<UnsupportedOperationException> { GroupMembership(1, 2L).withType(3) }
        assertFailsWith<UnsupportedOperationException> { Gender(1, "F").withType(1) }
        assertEquals(Gender(1, "F"), Gender(1, "F").withLabel("ignored"))
        assertFailsWith<UnsupportedOperationException> { CommPref(1, "+1").withType(0) }
        assertEquals(CommPref(1, "+1"), CommPref(1, "+1").withLabel("ignored"))
    }

    @Test
    fun `contact derived views fall back to blanks`() {
        val c = contact()
        assertEquals("", c.name.value)
        assertEquals(null, c.photo)
        assertEquals("", c.org.company)
        assertEquals("", c.note.content)
        assertEquals(null, c.birthday)
    }

    // -- computeSimLoss ---------------------------------------------------------------

    @Test
    fun `bare contact with one phone loses nothing`() {
        assertTrue(computeSimLoss(contact(details(phones = listOf(phone())))).isEmpty())
    }

    @Test
    fun `every rich field is reported`() {
        val d = details(
            phones = listOf(phone(1), phone(2)),
            emails = listOf(email(1), email(2)),
            addresses = listOf(Address(1, "x", 1)),
            dates = listOf(Event(1, LocalDate(1990, 1, 1), 3)),
            photos = listOf(Photo(1, "b")),
            orgs = listOf(Organization(1, "Acme")),
            notes = listOf(Note(1, "keep")),
            nicknames = listOf(Nickname(1, "Al", 1)),
            groups = listOf(GroupMembership(1, 3L)),
            genders = listOf(Gender(1, Gender.MALE)),
            commPrefs = listOf(CommPref(id = 9L, number = "+1555")),
        )
        val c = contact(d, favorite = true, ringtone = "content://ring")
        assertEquals(
            setOf(
                SimDroppedField.PHOTO, SimDroppedField.EXTRA_PHONES, SimDroppedField.EXTRA_EMAILS,
                SimDroppedField.ADDRESSES, SimDroppedField.DATES, SimDroppedField.ORGANIZATION,
                SimDroppedField.NOTE, SimDroppedField.NICKNAME, SimDroppedField.GROUPS,
                SimDroppedField.GENDER, SimDroppedField.COMM_PREF, SimDroppedField.RINGTONE,
                SimDroppedField.FAVORITE,
            ),
            computeSimLoss(c).toSet(),
        )
    }

    @Test
    fun `blank rich fields are not losses`() {
        val d = details(
            orgs = listOf(Organization(1, "  ")),
            notes = listOf(Note(1, "")),
            nicknames = listOf(Nickname(1, " ", 1)),
            genders = listOf(Gender(1, "")),
        )
        assertTrue(computeSimLoss(contact(d)).isEmpty())
    }
}
