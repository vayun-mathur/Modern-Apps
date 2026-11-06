package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.PollOptionData
import com.vayunmathur.communicate.data.whatsapp.Reaction
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): WhatsAppServiceData serialize/parse round-trip. Pure kotlinx.serialization.
 */
class W5Slice1ServiceDataTest {

    @Test
    fun roundTrip_fullPayload() {
        val original = WhatsAppServiceData(
            senderName = "Alice",
            senderJid = "111@s.whatsapp.net",
            reactions = listOf(Reaction("👍", 2), Reaction("❤️", 1)),
            isEdited = true,
            pollQuestion = "Lunch?",
            pollOptions = listOf(PollOptionData("pizza", 3, listOf("a", "b"))),
            quotedMessageId = "Q1",
            quotedBody = "quoted",
            quotedSender = "bob",
            isGroup = true,
            groupParticipantCount = 7,
            mediaUrl = "https://x/y.jpg",
            mediaMime = "image/jpeg",
            mediaName = "photo.jpg",
        )
        val back = WhatsAppServiceData.parse(original.serialize())!!
        assertEquals(original, back)
    }

    @Test
    fun roundTrip_defaultsSurvive() {
        val back = WhatsAppServiceData.parse(WhatsAppServiceData().serialize())!!
        assertEquals(WhatsAppServiceData(), back)
        assertFalse(back.isEdited)
        assertFalse(back.isRevoked)
    }

    @Test
    fun parse_nullBlankOrGarbage_returnsNull() {
        assertNull(WhatsAppServiceData.parse(null))
        assertNull(WhatsAppServiceData.parse(""))
        assertNull(WhatsAppServiceData.parse("   "))
        assertNull(WhatsAppServiceData.parse("not json"))
    }

    @Test
    fun parse_unknownKeys_ignored() {
        val back = WhatsAppServiceData.parse("""{"senderName":"Al","futureField":123}""")!!
        assertEquals("Al", back.senderName)
    }

    @Test
    fun parse_partialJson_usesDefaults() {
        val back = WhatsAppServiceData.parse("""{"isRevoked":true,"groupParticipantCount":4}""")!!
        assertTrue(back.isRevoked)
        assertEquals(4, back.groupParticipantCount)
        assertEquals(emptyList(), back.reactions)
    }
}
