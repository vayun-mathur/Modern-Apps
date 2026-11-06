package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildPollVoteMessage
import com.vayunmathur.communicate.data.whatsapp.decryptPollVote
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): poll-vote build/decrypt round-trip (WhatsAppProtocolCrypto.kt).
 * Robolectric: the decrypt-failure path logs via Android Log, so the whole class
 * runs under Robolectric. Happy paths are behavior-identical on JVM.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1PollVoteTest {

    @Test
    fun pollVote_buildThenDecrypt_returnsOptionHashes() {
        val secret = ByteArray(32) { (it + 9).toByte() }
        val hashes = listOf(
            WhatsAppProtocol.sha256("Alpha".toByteArray()),
            WhatsAppProtocol.sha256("Beta".toByteArray()),
        )
        val msg = WhatsAppProtocol.buildPollVoteMessage(
            chatJid = "123@g.us",
            pollMessageId = "POLL1",
            pollCreatorJid = "111@s.whatsapp.net",
            pollFromMe = false,
            voterJid = "222@s.whatsapp.net",
            optionHashes = hashes,
            pollSecret = secret,
        )
        assertTrue(msg.hasPollUpdateMessage())
        val update = msg.pollUpdateMessage
        assertEquals("POLL1", update.pollCreationMessageKey.id)
        assertEquals("111@s.whatsapp.net", update.pollCreationMessageKey.participant)
        assertTrue(update.senderTimestampMS > 0)

        val back = WhatsAppProtocol.decryptPollVote(update, "POLL1", "111@s.whatsapp.net", "222@s.whatsapp.net", secret)
        assertTrue(back != null)
        assertEquals(2, back!!.size)
        assertTrue(back[0].contentEquals(hashes[0]))
        assertTrue(back[1].contentEquals(hashes[1]))
    }

    @Test
    fun pollVote_wrongSecret_returnsNull() {
        val secret = ByteArray(32) { 1 }
        val msg = WhatsAppProtocol.buildPollVoteMessage(
            "123@g.us", "P1", "111@s.whatsapp.net", false, "222@s.whatsapp.net",
            listOf(WhatsAppProtocol.sha256("A".toByteArray())), secret,
        )
        assertEquals(
            null,
            WhatsAppProtocol.decryptPollVote(
                msg.pollUpdateMessage, "P1", "111@s.whatsapp.net", "222@s.whatsapp.net", ByteArray(32) { 2 },
            ),
        )
    }

    @Test
    fun pollVote_fromMeGroup_omitsParticipant() {
        val msg = WhatsAppProtocol.buildPollVoteMessage(
            "123@g.us", "P2", "me@s.whatsapp.net", true, "me@s.whatsapp.net",
            listOf(WhatsAppProtocol.sha256("A".toByteArray())), ByteArray(32) { 3 },
        )
        assertTrue(!msg.pollUpdateMessage.pollCreationMessageKey.hasParticipant())
    }

}
