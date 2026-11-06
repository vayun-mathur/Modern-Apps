package com.vayunmathur.communicate.whatsapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.ContactSuggestion
import com.vayunmathur.communicate.data.whatsapp.MessageSource
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.cacheNotifyName
import com.vayunmathur.communicate.data.whatsapp.extractJid
import com.vayunmathur.communicate.data.whatsapp.extractMessageId
import com.vayunmathur.communicate.data.whatsapp.generateClientId
import com.vayunmathur.communicate.data.whatsapp.generateMessageId
import com.vayunmathur.communicate.data.whatsapp.generateRef
import com.vayunmathur.communicate.data.whatsapp.generateToken
import com.vayunmathur.communicate.data.whatsapp.getContactSuggestions
import com.vayunmathur.communicate.data.whatsapp.inflateZlib
import com.vayunmathur.communicate.data.whatsapp.isLoggedIn
import com.vayunmathur.communicate.data.whatsapp.isOwnJid
import com.vayunmathur.communicate.data.whatsapp.resolveChatSender
import com.vayunmathur.communicate.data.whatsapp.resolveJID
import com.vayunmathur.communicate.data.whatsapp.resolveMentionsInBody
import com.vayunmathur.communicate.data.whatsapp.resolveName
import com.vayunmathur.communicate.data.whatsapp.trackUndecryptable
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): WhatsAppClient pure helpers (WhatsAppClientUtils.kt) that need no
 * socket/auth: JID extraction, own-JID checks, LID resolution, zlib inflate,
 * mention resolution, chat-sender routing, contact suggestions, id generators.
 *
 * Robolectric: the client singleton touches Android (Base64, appContext on some
 * paths); these tests init() the singleton with the test application context.
 * NOTE: if the lead fixes Robolectric exec capture, this file's coverage will
 * start counting; until then it still guards behavior.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1ClientUtilsTest {

    // NOTE: WhatsAppClient.init() is NOT called: it builds the SQLCipher database via
    // the AndroidKeyStore, which Robolectric does not provide. The helpers under test
    // (extractJid, resolveJID, inflateZlib, generators, caches) do not touch appContext/db.

    @Test
    fun extractJid_stripsAllWaPrefixes() {
        assertEquals("1@s.whatsapp.net", WhatsAppClient.extractJid("wa:wa:1@s.whatsapp.net"))
        assertEquals("1@s.whatsapp.net", WhatsAppClient.extractJid("wa:1@s.whatsapp.net"))
        assertEquals("1@s.whatsapp.net", WhatsAppClient.extractJid("1@s.whatsapp.net"))
        assertNull(WhatsAppClient.extractJid("wa:"))
    }

    @Test
    fun extractMessageId_stripsWaPrefixes() {
        assertEquals("ABC", WhatsAppClient.extractMessageId("wa:wa:ABC"))
        assertEquals("ABC", WhatsAppClient.extractMessageId("ABC"))
    }

    @Test
    fun isOwnJid_cases() {
        assertFalse(WhatsAppClient.isOwnJid(null))
        assertFalse(WhatsAppClient.isOwnJid(""))
        // no auth loaded in test: only structural checks
        assertFalse(WhatsAppClient.isOwnJid("someone@s.whatsapp.net"))
    }

    @Test
    fun isOwnJid_withAuth_matchesWidAndLidBareUser() {
        WhatsAppClient.authData = WhatsAppAuthData(
            phoneNumber = "+15550001", pushName = "", wid = "15550001@s.whatsapp.net",
            noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
            registrationId = 1, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
            signedPreKeySignature = "", lid = "12345@lid",
        )
        try {
            assertTrue(WhatsAppClient.isOwnJid("15550001@s.whatsapp.net"))
            assertTrue(WhatsAppClient.isOwnJid("15550001:1@s.whatsapp.net"))
            assertTrue(WhatsAppClient.isOwnJid("12345@lid"))
            assertFalse(WhatsAppClient.isOwnJid("99999@s.whatsapp.net"))
        } finally {
            WhatsAppClient.authData = null
        }
    }

    @Test
    fun resolveJID_cases() {
        // non-LID passes through
        assertEquals("1@s.whatsapp.net", WhatsAppClient.resolveJID("1@s.whatsapp.net"))
        // cached mapping wins
        WhatsAppClient.lidToPhoneMap["abc@lid"] = "1@s.whatsapp.net"
        try {
            assertEquals("1@s.whatsapp.net", WhatsAppClient.resolveJID("abc@lid"))
            // bot JIDs pass through
            assertEquals("x@bot", WhatsAppClient.resolveJID("x@bot"))
            // unknown LID passes through
            assertEquals("zzz@lid", WhatsAppClient.resolveJID("zzz@lid"))
        } finally {
            WhatsAppClient.lidToPhoneMap.clear()
        }
    }

    @Test
    fun resolveJID_ownLid_mapsToWid() {
        WhatsAppClient.authData = WhatsAppAuthData(
            phoneNumber = "+1", pushName = "", wid = "1555@s.whatsapp.net",
            noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
            registrationId = 1, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
            signedPreKeySignature = "", lid = "777@lid",
        )
        try {
            assertEquals("1555@s.whatsapp.net", WhatsAppClient.resolveJID("777@lid"))
        } finally {
            WhatsAppClient.authData = null
        }
    }

    @Test
    fun trackUndecryptable_countsPerSender() {
        val node = WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to "t1@s.whatsapp.net"))
        WhatsAppClient.trackUndecryptable(node)
        WhatsAppClient.trackUndecryptable(node)
        assertEquals(2, WhatsAppClient.undecryptableTracker["t1@s.whatsapp.net"])
        // missing from: no-op
        WhatsAppClient.trackUndecryptable(WhatsAppProtocol.Node(tag = "message"))
        WhatsAppClient.undecryptableTracker.clear()
    }

    @Test
    fun inflateZlib_roundTrips() {
        val raw = "history blob bytes 1234567890 repeated ".repeat(20).toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        assertTrue(WhatsAppClient.inflateZlib(bos.toByteArray()).contentEquals(raw))
    }

    @Test
    fun generators_shapes() {
        assertTrue(WhatsAppClient.generateMessageId().startsWith("3EB0"))
        assertTrue(WhatsAppClient.generateRef().isNotEmpty())
        assertTrue(WhatsAppClient.generateClientId().isNotEmpty())
        assertTrue(WhatsAppClient.generateToken().isNotEmpty())
        // 24 random bytes -> 32 base64 chars (no wrap)
        assertEquals(32, WhatsAppClient.generateToken().length)
    }

    @Test
    fun resolveName_botAndPhone() = runTest {
        assertEquals("Meta AI", WhatsAppClient.resolveName("123@bot"))
        assertEquals("+15550001", WhatsAppClient.resolveName("15550001@s.whatsapp.net"))
        assertEquals("+15550001", WhatsAppClient.resolveName("15550001:1@s.whatsapp.net"))
        // cached notify name wins
        WhatsAppClient.nameCache["9@s.whatsapp.net"] = "Zed"
        try {
            assertEquals("Zed", WhatsAppClient.resolveName("9@s.whatsapp.net"))
        } finally {
            WhatsAppClient.nameCache.remove("9@s.whatsapp.net")
        }
    }

    @Test
    fun resolveMentionsInBody_replacesTokens() = runTest {
        assertEquals("no mentions", WhatsAppClient.resolveMentionsInBody("no mentions", emptyList()))
        assertEquals("hi @+1555", WhatsAppClient.resolveMentionsInBody("hi @1555", listOf("1555@s.whatsapp.net")))
    }

    @Test
    fun resolveChatSender_groupOwnParticipant_isFromMe() {
        val msg = WhatsAppMessage(
            id = "g1", from = "9@g.us", to = "", body = "x", timestamp = 1, type = "text",
            participant = "15550001@s.whatsapp.net",
        )
        WhatsAppClient.authData = WhatsAppAuthData(
            phoneNumber = "+1", pushName = "", wid = "15550001@s.whatsapp.net",
            noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
            registrationId = 1, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
            signedPreKeySignature = "",
        )
        try {
            val (sender, fromMe) = WhatsAppClient.resolveChatSender(msg)
            assertEquals("15550001@s.whatsapp.net", sender)
            assertTrue(fromMe)
        } finally {
            WhatsAppClient.authData = null
        }
    }

    @Test
    fun cacheNotifyName_storesNonEmpty() {
        WhatsAppClient.cacheNotifyName(
            WhatsAppProtocol.Node(tag = "message", attrs = mapOf("notify" to "Al")),
            "1@s.whatsapp.net",
        )
        try {
            assertEquals("Al", WhatsAppClient.nameCache["1@s.whatsapp.net"])
        } finally {
            WhatsAppClient.nameCache.remove("1@s.whatsapp.net")
        }
        WhatsAppClient.cacheNotifyName(WhatsAppProtocol.Node(tag = "message"), "2@s.whatsapp.net")
        assertNull(WhatsAppClient.nameCache["2@s.whatsapp.net"])
    }

    @Test
    fun contactSuggestions_filtersCache() {
        WhatsAppClient.nameCache["a"] = "Alice Anderson"
        WhatsAppClient.nameCache["b"] = "Bob"
        try {
            val hits = WhatsAppClient.getContactSuggestions("ali")
            assertEquals(1, hits.size)
            assertEquals("Alice Anderson", hits[0].displayName)
            assertEquals(MessageSource.WHATSAPP, hits[0].source)
            assertEquals(ContactSuggestion("Alice Anderson", null, null, MessageSource.WHATSAPP), hits[0])
        } finally {
            WhatsAppClient.nameCache.clear()
        }
    }

    @Test
    fun isLoggedIn_falseWithoutAuth() {
        WhatsAppClient.authData = null
        assertFalse(WhatsAppClient.isLoggedIn())
    }
}
