package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.CommAction
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.CommPrefResolver
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactPlatforms
import com.vayunmathur.contacts.data.VcfUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins comm-pref handling end to end: the [CommPref] model codec, the
 * [CommPrefResolver] validity matrix, and the VCF export/import round trip.
 * No Android needed (I/O is in-memory).
 */
class CommPrefTest {

    private val phones = listOf(
        PhoneNumber(1, "+14155550132", 2),
        PhoneNumber(2, "+14155550199", 2),
    )

    private fun platforms(
        signalCall: Long? = 10L,
        whatsAppCall: Long? = 11L,
        telegramCall: Long? = null,
        signalMessage: Long? = 20L,
        whatsAppMessage: Long? = 21L,
        telegramMessage: Long? = null,
        signalVideo: Long? = 30L,
        whatsAppVideo: Long? = 31L,
        telegramVideo: Long? = null,
    ) = ContactPlatforms(
        whatsAppCallId = whatsAppCall,
        whatsAppVideoId = whatsAppVideo,
        whatsAppMessageId = whatsAppMessage,
        signalCallId = signalCall,
        signalVideoId = signalVideo,
        signalMessageId = signalMessage,
        telegramCallId = telegramCall,
        telegramVideoId = telegramVideo,
        telegramMessageId = telegramMessage,
    )

    private fun contactWith(pref: CommPref?): Contact =
        Contact(
            1, null, null, false,
            ContactDetails.empty().copy(
                names = listOf(Name(0, "", "Ada", "", "Lovelace", "")),
                phoneNumbers = phones,
                emails = listOf(Email(0, "ada@example.com", 1)),
                commPrefs = listOfNotNull(pref),
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
    fun storageKeyRoundTrips() {
        assertEquals(CommPlatform.SIGNAL, CommPlatform.fromStorageKey("signal"))
        assertEquals(CommPlatform.SIM, CommPlatform.fromStorageKey("SIM"))
        assertEquals(null, CommPlatform.fromStorageKey("pigeon"))
    }

    @Test
    fun withValueParsesPlatformAndNumber() {
        assertEquals(
            CommPref(0, "+14155550132", CommPlatform.WHATSAPP),
            CommPref(0, "", CommPlatform.SIM).withValue("whatsapp;+14155550132")
        )
    }

    @Test
    fun simIsAlwaysValidForCallAndMessage() {
        val pref = CommPref(0, "+14155550132", CommPlatform.SIM)
        assertTrue(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.CALL, phones, ContactPlatforms(), false
            )
        )
        assertTrue(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.MESSAGE, phones, ContactPlatforms(), false
            )
        )
    }

    @Test
    fun unknownPlatformFallsBackToSimOnly() {
        val pref = CommPref(0, "+14155550132", CommPlatform.SIGNAL)
        // No platform rows: signal pref invalid, only SIM offered.
        assertFalse(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.CALL, phones, ContactPlatforms(), false
            )
        )
        assertTrue(
            CommPrefResolver.isSimOnly(CommAction.CALL, ContactPlatforms(), false)
        )
        assertEquals(
            listOf(CommPlatform.SIM),
            CommPrefResolver.availablePlatformsFor(
                CommAction.CALL, ContactPlatforms(), false
            )
        )
    }

    @Test
    fun staleNumberInvalidatesPref() {
        val pref = CommPref(0, "+19998887777", CommPlatform.SIM)
        assertFalse(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.CALL, phones, ContactPlatforms(), false
            )
        )
    }

    @Test
    fun meetIsVideoOnly() {
        val pref = CommPref(0, "+14155550132", CommPlatform.MEET)
        assertTrue(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.VIDEO, phones, ContactPlatforms(), true
            )
        )
        assertFalse(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.CALL, phones, ContactPlatforms(), true
            )
        )
        assertFalse(
            CommPrefResolver.isPrefValidFor(
                pref, CommAction.VIDEO, phones, ContactPlatforms(), false
            )
        )
    }

    @Test
    fun signalBeatsWhatsappBeatsSimInOrder() {
        val order = CommPrefResolver.availablePlatformsFor(
            CommAction.CALL, platforms(), false
        )
        assertEquals(
            listOf(CommPlatform.SIGNAL, CommPlatform.WHATSAPP, CommPlatform.SIM),
            order
        )
    }

    @Test
    fun exportWritesCommPrefLine() {
        val blob = export(contactWith(CommPref(0, "+14155550132", CommPlatform.SIGNAL)))
        assertTrue(blob.contains("X-COMM-PREF:signal;+14155550132\r\n"), blob)
    }

    @Test
    fun exportSkipsAbsentPref() {
        val blob = export(contactWith(null))
        assertFalse(blob.contains("X-COMM-PREF:"), blob)
    }

    @Test
    fun prefRoundTrips() {
        val back = import(export(contactWith(CommPref(0, "+14155550132", CommPlatform.WHATSAPP))))
        assertEquals(
            listOf(CommPref(0, "+14155550132", CommPlatform.WHATSAPP)),
            back.details.commPrefs
        )
    }

    @Test
    fun importDropsInvalidPref() {
        val back = import("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nX-COMM-PREF:pigeon;+14155550132\r\nEND:VCARD\r\n")
        assertTrue(back.details.commPrefs.isEmpty())
    }
}
