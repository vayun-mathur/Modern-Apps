package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.CommAction
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.CommPrefResolver
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactPlatforms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for the comm-pref decision logic ([CommPrefResolver]):
 * which platforms the picker offers per action, when a saved pref stays
 * valid, and when the picker collapses to SIM-only.
 */
class CommPrefResolverTest {

    private val empty = ContactPlatforms()
    private val full = ContactPlatforms(
        whatsAppCallId = 1L, whatsAppVideoId = 2L, whatsAppMessageId = 3L,
        signalCallId = 4L, signalVideoId = 5L, signalMessageId = 6L,
        telegramCallId = 7L, telegramVideoId = 8L, telegramMessageId = 9L,
    )

    @Test
    fun `call and message always offer sim last`() {
        assertEquals(
            listOf(CommPlatform.SIGNAL, CommPlatform.WHATSAPP, CommPlatform.TELEGRAM, CommPlatform.SIM),
            CommPrefResolver.availablePlatformsFor(CommAction.CALL, full, false),
        )
        assertEquals(listOf(CommPlatform.SIM), CommPrefResolver.availablePlatformsFor(CommAction.CALL, empty, false))
        assertEquals(
            listOf(CommPlatform.SIGNAL, CommPlatform.WHATSAPP, CommPlatform.TELEGRAM, CommPlatform.SIM),
            CommPrefResolver.availablePlatformsFor(CommAction.MESSAGE, full, false),
        )
    }

    @Test
    fun `video has no sim and meet only when installed`() {
        assertEquals(
            listOf(CommPlatform.WHATSAPP, CommPlatform.SIGNAL, CommPlatform.TELEGRAM),
            CommPrefResolver.availablePlatformsFor(CommAction.VIDEO, full, false),
        )
        assertEquals(
            listOf(CommPlatform.MEET, CommPlatform.WHATSAPP, CommPlatform.SIGNAL, CommPlatform.TELEGRAM),
            CommPrefResolver.availablePlatformsFor(CommAction.VIDEO, full, true),
        )
        assertTrue(CommPrefResolver.availablePlatformsFor(CommAction.VIDEO, empty, false).isEmpty())
        assertEquals(
            listOf(CommPlatform.MEET),
            CommPrefResolver.availablePlatformsFor(CommAction.VIDEO, empty, true),
        )
    }

    @Test
    fun `pref valid only when number exists and platform offered`() {
        val phones = listOf(PhoneNumber(1, "+1 415-555-0111", 2))
        val pref = CommPref(1, "+14155550111", CommPlatform.WHATSAPP)
        assertTrue(CommPrefResolver.isPrefValidFor(pref, CommAction.MESSAGE, phones, full, false))
        // Number gone from the contact.
        assertFalse(CommPrefResolver.isPrefValidFor(pref, CommAction.MESSAGE, emptyList(), full, false))
        // Platform no longer installed.
        assertFalse(CommPrefResolver.isPrefValidFor(pref, CommAction.MESSAGE, phones, empty, false))
        // Null or blank pref.
        assertFalse(CommPrefResolver.isPrefValidFor(null, CommAction.MESSAGE, phones, full, false))
        assertFalse(
            CommPrefResolver.isPrefValidFor(pref.copy(number = "  "), CommAction.MESSAGE, phones, full, false),
        )
    }

    @Test
    fun `sim only when nothing else available`() {
        assertTrue(CommPrefResolver.isSimOnly(CommAction.CALL, empty, false))
        assertTrue(CommPrefResolver.isSimOnly(CommAction.MESSAGE, empty, true))
        assertFalse(CommPrefResolver.isSimOnly(CommAction.CALL, full, false))
        // Video never collapses to SIM (SIM is not a video option).
        assertFalse(CommPrefResolver.isSimOnly(CommAction.VIDEO, empty, false))
    }

    @Test
    fun `platform flags aggregate the row ids`() {
        assertFalse(empty.hasAnyPlatform)
        assertTrue(full.hasWhatsApp)
        assertTrue(full.hasSignal)
        assertTrue(full.hasTelegram)
        assertTrue(full.hasAnyPlatform)
        assertFalse(ContactPlatforms(whatsAppCallId = 1L).hasSignal)
    }
}
