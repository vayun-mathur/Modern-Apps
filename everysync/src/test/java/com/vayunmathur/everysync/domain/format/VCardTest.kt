package com.vayunmathur.everysync.domain.format

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.TypedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VCardTest {
    @Test fun parseFullCard() {
        val c = VCard.parse(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:u1\r\nN:Doe;Jane;M;Dr;Jr\r\nFN:Dr Jane M Doe Jr\r\nORG:Acme;Dept\r\nNOTE:hi\\, there\\nline2\r\nBDAY:19900102\r\nTEL;TYPE=CELL:+123\r\nTEL;TYPE=WORK:+456\r\nTEL;TYPE=HOME:+789\r\nTEL;TYPE=FAX:+000\r\nTEL:+111\r\nEMAIL;TYPE=WORK:j@w\r\nEMAIL;TYPE=HOME:j@h\r\nEMAIL:j@o\r\nADR;TYPE=WORK:;;1 Main St;Springfield;;12345;US\r\nADR;TYPE=HOME:;;2 Oak\r\nADR:;;3 Pine\r\nEND:VCARD\r\n"
        )
        assertEquals("u1", c.uid); assertEquals("Dr Jane M Doe Jr", c.displayName)
        assertEquals("Jane", c.firstName); assertEquals("Doe", c.lastName); assertEquals("M", c.middleName)
        assertEquals("Dr", c.prefix); assertEquals("Jr", c.suffix); assertEquals("Acme", c.organization)
        assertEquals("hi, there\nline2", c.note); assertEquals("1990-01-02", c.birthday)
        assertEquals(Phone.TYPE_MOBILE, c.phones[0].type); assertEquals(Phone.TYPE_WORK, c.phones[1].type)
        assertEquals(Phone.TYPE_HOME, c.phones[2].type); assertEquals(Phone.TYPE_FAX_HOME, c.phones[3].type)
        assertEquals(Phone.TYPE_OTHER, c.phones[4].type)
        assertEquals(Email.TYPE_WORK, c.emails[0].type); assertEquals(Email.TYPE_HOME, c.emails[1].type); assertEquals(Email.TYPE_OTHER, c.emails[2].type)
        assertEquals(StructuredPostal.TYPE_WORK, c.addresses[0].type); assertEquals("1 Main St, Springfield, 12345, US", c.addresses[0].value)
        assertEquals(StructuredPostal.TYPE_HOME, c.addresses[1].type); assertEquals(StructuredPostal.TYPE_OTHER, c.addresses[2].type)
    }
    @Test fun fallbackUidAndDisplayFromNameParts() {
        val c = VCard.parse("BEGIN:VCARD\nN:Smith;Ann;;Ms;\nEND:VCARD\n", fallbackUid = "fb")
        assertEquals("fb", c.uid); assertEquals("Ms Ann Smith", c.displayName)
        val blankUid = VCard.parse("UID:\nFN:X\n", fallbackUid = "fb2")
        assertEquals("fb2", blankUid.uid); assertEquals("X", blankUid.displayName)
    }
    @Test fun unfoldingAndGarbageLines() {
        val c = VCard.parse("BEGIN:VCARD\nUID:u9\nFN:Long\n Name\nNoColonLine\n:bad\nNOTE:a\n\tb\nEND:VCARD\n")
        assertEquals("LongName", c.displayName); assertEquals("ab", c.note)
    }
    @Test fun bdayVariants() {
        assertEquals("2020-02-03", VCard.parse("BDAY:2020-02-03\n").birthday)
        assertEquals("2020-02-03", VCard.parse("BDAY:20200203\n").birthday)
        assertEquals("unknown", VCard.parse("BDAY:unknown\n").birthday)
        assertEquals(null, VCard.parse("FN:x\n").birthday)
    }
    @Test fun serializeRoundTrip() {
        val orig = RemoteContact(uid="u2", displayName="Doe, Jane", firstName="Jane", lastName="Doe", middleName="Q", prefix="Dr", suffix="PhD",
            organization="Org, Inc", note="a;b\nc\\d", phones=listOf(TypedValue("+1", Phone.TYPE_MOBILE), TypedValue("+2", Phone.TYPE_WORK), TypedValue("+3", Phone.TYPE_HOME), TypedValue("+4", Phone.TYPE_FAX_WORK), TypedValue("+5", 999)),
            emails=listOf(TypedValue("a@b", Email.TYPE_WORK), TypedValue("c@d", Email.TYPE_HOME), TypedValue("e@f", 999)),
            addresses=listOf(TypedValue("1 St", StructuredPostal.TYPE_WORK), TypedValue("2 St", StructuredPostal.TYPE_HOME), TypedValue("3 St", 999)), birthday="2000-01-01")
        val text = VCard.serialize(orig)
        assertTrue(text.startsWith("BEGIN:VCARD\r\n")); assertTrue(text.contains("VERSION:3.0"))
        val back = VCard.parse(text)
        assertEquals(orig.uid, back.uid); assertEquals(orig.displayName, back.displayName); assertEquals(orig.firstName, back.firstName)
        assertEquals(orig.organization, back.organization); assertEquals(orig.note, back.note); assertEquals(orig.birthday, back.birthday)
        assertEquals(orig.phones.map{it.value}, back.phones.map{it.value}); assertEquals(orig.emails.map{it.value}, back.emails.map{it.value})
    }
    @Test fun serializeOmitsBlankOptionalFields() {
        val text = VCard.serialize(RemoteContact(uid="x", displayName="X"))
        assertTrue(!text.contains("ORG:")); assertTrue(!text.contains("BDAY:")); assertTrue(!text.contains("NOTE:"))
        assertTrue(text.contains("TEL").not())
    }
    @Test fun literalBackslashSequencesRoundTrip() {
        // Note contains a literal backslash followed by 'n': on the wire
        // the backslash is doubled and parsing must not produce a newline.
        val orig = RemoteContact(uid = "u3", displayName = "X", note = "a\\nb")
        val back = VCard.parse(VCard.serialize(orig))
        assertEquals(orig.note, back.note)
    }
    @Test fun lowercaseParamsAndMobileAlias() {
        val c = VCard.parse("tel;type=mobile:+9\nemail;type=home:a@b\n")
        assertEquals(Phone.TYPE_MOBILE, c.phones[0].type); assertEquals(Email.TYPE_HOME, c.emails[0].type)
    }
}
