package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.SimDroppedField
import com.vayunmathur.contacts.data.VcfUtils
import com.vayunmathur.contacts.platform.computeSimLoss
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins gender handling end to end through real production code: the
 * [Gender] model codec, the VCF export/import round trip, and SIM loss
 * classification. No Android needed (provider constants are inlined,
 * I/O is in-memory).
 */
class VCardGenderTest {

    private fun contactWith(vararg genders: Gender): Contact =
        Contact(
            1, null, null, false,
            ContactDetails.empty().copy(
                names = listOf(Name(0, "", "Ada", "", "Lovelace", "")),
                phoneNumbers = listOf(PhoneNumber(0, "+14155550132", 2)),
                emails = listOf(Email(0, "ada@example.com", 1)),
                genders = genders.toList(),
            ),
        )

    private fun export(contact: Contact): String = runBlocking {
        val out = ByteArrayOutputStream()
        VcfUtils.exportContacts(listOf(contact), out)
        out.toString("UTF-8")
    }

    private fun import(blob: String): Contact =
        VcfUtils.parseContacts(ByteArrayInputStream(blob.toByteArray(Charsets.UTF_8))).single()

    @Test
    fun canonicalCodeMapsLettersAndWords() {
        assertEquals("F", Gender.canonicalCode("F"))
        assertEquals("F", Gender.canonicalCode("female"))
        assertEquals("M", Gender.canonicalCode("M"))
        assertEquals("M", Gender.canonicalCode("Male"))
        assertEquals("O", Gender.canonicalCode("O"))
        assertEquals("O", Gender.canonicalCode("other"))
        assertEquals("U", Gender.canonicalCode("U"))
        assertEquals("U", Gender.canonicalCode(""))
        assertNull(Gender.canonicalCode("banana"))
    }

    @Test
    fun valueFormatsLetterAndOtherText() {
        assertEquals("F", Gender(0, "F").value)
        assertEquals("M", Gender(0, "M", "ignored").value)
        assertEquals("O", Gender(0, "O").value)
        assertEquals("O;Nonbinary", Gender(0, "O", "Nonbinary").value)
        assertEquals("U", Gender(0, "U").value)
    }

    @Test
    fun withValueParsesLetterAndText() {
        assertEquals(Gender(0, "M", ""), Gender(0, "", "").withValue("M"))
        assertEquals(Gender(0, "O", "X;Y"), Gender(0, "", "").withValue("O;X;Y"))
    }

    @Test
    fun exportWritesGenderLine() {
        val blob = export(contactWith(Gender(0, "F")))
        assertTrue(blob.contains("GENDER:F\r\n"), blob)
    }

    @Test
    fun exportWritesOtherWithText() {
        val blob = export(contactWith(Gender(0, "O", "Nonbinary")))
        assertTrue(blob.contains("GENDER:O;Nonbinary\r\n"), blob)
    }

    @Test
    fun exportSkipsBlankGender() {
        val blob = export(contactWith())
        assertFalse(blob.contains("GENDER:"), blob)
    }

    @Test
    fun importParsesGenderLetter() {
        val back = import("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nGENDER:M\r\nEND:VCARD\r\n")
        assertEquals(listOf(Gender(0, "M", "")), back.details.genders)
    }

    @Test
    fun importParsesOtherWithText() {
        val back = import("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nGENDER:O;Genderqueer\r\nEND:VCARD\r\n")
        assertEquals(listOf(Gender(0, "O", "Genderqueer")), back.details.genders)
    }

    @Test
    fun importLowercaseLetterParses() {
        val back = import("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nGENDER:f\r\nEND:VCARD\r\n")
        assertEquals(listOf(Gender(0, "F", "")), back.details.genders)
    }

    @Test
    fun importIgnoresUnrecognizedLetter() {
        val back = import("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nGENDER:Z\r\nEND:VCARD\r\n")
        assertTrue(back.details.genders.isEmpty())
    }

    @Test
    fun genderRoundTripsWithEscapedText() {
        val original = contactWith(Gender(0, "O", "Non-binary; queer"))
        val back = import(export(original))
        assertEquals(listOf(Gender(0, "O", "Non-binary; queer")), back.details.genders)
    }

    @Test
    fun genderRoundTripsLetter() {
        val back = import(export(contactWith(Gender(0, "M"))))
        assertEquals(listOf(Gender(0, "M", "")), back.details.genders)
    }

    @Test
    fun simLossIncludesGender() {
        val withGender = contactWith(Gender(0, "F"))
        assertTrue(computeSimLoss(withGender).contains(SimDroppedField.GENDER))
        assertFalse(computeSimLoss(contactWith()).contains(SimDroppedField.GENDER))
    }
}
