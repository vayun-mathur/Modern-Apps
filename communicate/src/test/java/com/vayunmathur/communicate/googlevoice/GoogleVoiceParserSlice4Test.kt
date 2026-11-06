package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GvCallType
import com.vayunmathur.communicate.data.googlevoice.GvFolder
import com.vayunmathur.communicate.data.googlevoice.buildAccountBody
import com.vayunmathur.communicate.data.googlevoice.buildBatchUpdateBody
import com.vayunmathur.communicate.data.googlevoice.buildListBody
import com.vayunmathur.communicate.data.googlevoice.buildSearchBody
import com.vayunmathur.communicate.data.googlevoice.buildSendSmsBody
import com.vayunmathur.communicate.data.googlevoice.quote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the Google Voice request builders and response parsing
 * ([GoogleVoiceParser]) beyond the shapes already pinned by [GoogleVoiceParserTest].
 *
 * Pure JVM: every positional wire shape is asserted against hand-built bodies,
 * so a slot shift is caught without a live account.
 */
class GoogleVoiceParserSlice4Test {

    // -- request builders -------------------------------------------------------

    @Test
    fun buildListBody_honoursFolderPageSizeAndWindow() {
        assertEquals("[2,20,15,null,null,[null,1,1,1]]", GoogleVoiceParser.buildListBody(GvFolder.Inbox))
        assertEquals("[3,100,50,null,null,[null,1,1,1]]", GoogleVoiceParser.buildListBody(GvFolder.Calls, 100, 50))
        assertEquals("[4,20,15,null,null,[null,1,1,1]]", GoogleVoiceParser.buildListBody(GvFolder.Voicemail))
        assertEquals("[1,20,15,null,null,[null,1,1,1]]", GoogleVoiceParser.buildListBody(GvFolder.All))
        assertEquals("[5,20,15,null,null,[null,1,1,1]]", GoogleVoiceParser.buildListBody(GvFolder.Spam))
    }

    @Test
    fun buildSearchBody_honoursLimit() {
        assertEquals(
            "[\"query\",200,null,null,null,[null,1,1,1]]",
            GoogleVoiceParser.buildSearchBody("query"),
        )
        assertEquals(
            "[\"query\",10,null,null,null,[null,1,1,1]]",
            GoogleVoiceParser.buildSearchBody("query", 10),
        )
    }

    @Test
    fun buildAccountBody_isNullPlusEmptyObject() {
        assertEquals("[null,{}]", GoogleVoiceParser.buildAccountBody())
    }

    @Test
    fun buildSendSmsBody_appendsBotTokenWhenSupplied() {
        val body = GoogleVoiceParser.buildSendSmsBody("+1555", "hi", threadRemoteId = null, clientTxnId = 7L, botToken = "!tok")
        assertEquals("[null,null,null,null,\"hi\",null,[\"+1555\"],null,[7],null,[\"!tok\"]]", body)
    }

    @Test
    fun buildBatchUpdateBody_coversEveryAction() {
        assertEquals(
            "[[[[\"t.1\",null,null,1],[null,null,null,1],1]]]",
            GoogleVoiceParser.buildBatchUpdateBody("t.1", GoogleVoiceParser.ThreadAction.MarkRead),
        )
        assertEquals(
            "[[[[\"t.1\",null,null,0],[null,null,null,1],1]]]",
            GoogleVoiceParser.buildBatchUpdateBody("t.1", GoogleVoiceParser.ThreadAction.MarkUnread),
        )
        assertEquals(
            "[[[[\"t.1\",null,1],[null,null,1],1]]]",
            GoogleVoiceParser.buildBatchUpdateBody("t.1", GoogleVoiceParser.ThreadAction.Archive),
        )
        assertEquals(
            "[[[[\"t.1\",null,0],[null,null,1],1]]]",
            GoogleVoiceParser.buildBatchUpdateBody("t.1", GoogleVoiceParser.ThreadAction.Unarchive),
        )
    }

    @Test
    fun quote_escapesForEmbedding() {
        assertEquals("\"a\\\"b\"", GoogleVoiceParser.quote("a\"b"))
        assertEquals("\"plain\"", GoogleVoiceParser.quote("plain"))
    }

    @Test
    fun extractBotToken_findsTheBangToken() {
        val body = "[null,null,null,null,\"hi\",null,[\"+1555\"],null,[7],null,[\"!abc123\"]]"
        assertEquals("!abc123", GoogleVoiceParser.extractBotToken(body))
        assertNull(GoogleVoiceParser.extractBotToken("[null,null,[\"no-bang\"]]"))
        assertNull(GoogleVoiceParser.extractBotToken("not json"))
        assertNull(GoogleVoiceParser.extractBotToken(""))
    }

    // -- response parsing ---------------------------------------------------------

    @Test
    fun parseAccount_returnsNullOnGarbageAndFirstPhoneOtherwise() {
        assertNull(GoogleVoiceParser.parseAccount("not json").phoneNumber)
        assertNull(GoogleVoiceParser.parseAccount("").phoneNumber)
        assertNull(GoogleVoiceParser.parseAccount("[null,{}]").phoneNumber)
        assertEquals(
            "+15551234567",
            GoogleVoiceParser.parseAccount("[\"x\",\"+15551234567\",\"y\"]").phoneNumber,
        )
    }

    @Test
    fun parseThreads_returnsEmptyOnGarbageAndDedupes() {
        assertTrue(GoogleVoiceParser.parseThreads("nope").isEmpty())
        assertTrue(GoogleVoiceParser.parseThreads("").isEmpty())
        val one = "[[[\"t.+1415\",1,[[\"h1\",1786409649630,\"+1000\",[\"+1415\"],10,1,null,null,null," +
            "\"hi\",null,null,5,1,null,\"+1415\",0]],null,[[\"+1415\"]]]]]"
        assertEquals(1, GoogleVoiceParser.parseThreads(one).size)
        // Same record twice: distinct by id.
        val doubled = one.dropLast(1) + "," + one.substring(1)
        assertEquals(1, GoogleVoiceParser.parseThreads(doubled).size)
    }

    @Test
    fun parseThreads_sortsMessagesOldestFirstAndSnippetsNewest() {
        val body = "[[[\"t.+1415\",1,[" +
            "[\"n\",1786409649631,\"+1000\",[\"+1415\"],10,1,null,null,null,\"second\",null,null,5,1,null,\"+1415\",0]," +
            "[\"o\",1786409649630,\"+1000\",[\"+1415\"],11,1,null,null,null,\"first\",null,null,5,1,null,\"+1415\",0]" +
            "],null,[[\"+1415\"]]]]]"
        val thread = GoogleVoiceParser.parseThreads(body).single()
        assertEquals(listOf("first", "second"), thread.messages.map { it.text })
        assertEquals("second", thread.snippet)
        assertTrue(thread.messages[0].outgoing) // type 11
        assertFalse(thread.messages[1].outgoing) // type 10
        assertEquals(1786409649631L, thread.timestampMillis)
    }

    @Test
    fun parseThreads_skipsMessagesWithoutTimestamps() {
        val body = "[[[\"t.+1415\",1,[" +
            "[\"n\",null,\"+1000\",[\"+1415\"],10,1,null,null,null,\"no-ts\",null,null,5,1,null,\"+1415\",0]" +
            "],null,[[\"+1415\"]]]]]"
        val thread = GoogleVoiceParser.parseThreads(body).single()
        assertTrue(thread.messages.isEmpty())
        assertEquals("", thread.snippet)
    }

    @Test
    fun parseThreadMessages_missesOnUnknownThread() {
        val body = "[[[\"t.+1415\",1,[[\"h1\",1786409649630,\"+1000\",[\"+1415\"],10,1,null,null,null," +
            "\"hi\",null,null,5,1,null,\"+1415\",0]],null,[[\"+1415\"]]]]]"
        assertEquals(1, GoogleVoiceParser.parseThreadMessages(body, "t.+1415").size)
        assertTrue(GoogleVoiceParser.parseThreadMessages(body, "t.+9999").isEmpty())
        assertTrue(GoogleVoiceParser.parseThreadMessages("garbage", "t.+1415").isEmpty())
    }

    @Test
    fun parseCalls_classifiesDirectionAndMisses() {
        // Outgoing: own number equals the counterparty.
        val out = "[[[\"c.out\",1,[[\"h\",1786409649630,\"+1415\",[\"+1415\"],14,1,null,null,60," +
            "null,null,null,5,1,null,\"+1415\",0]],null]]]"
        val outgoing = GoogleVoiceParser.parseCalls(out).single()
        assertEquals(GvCallType.Outgoing, outgoing.type)
        assertEquals(60L, outgoing.durationSeconds)
        assertEquals("+1415", outgoing.phoneNumber)

        // Incoming with duration; missed when zero.
        val inc = "[[[\"c.inc\",1,[[\"h\",1786409649630,\"+1000\",[\"+1415\"],14,1,null,null,30," +
            "null,null,null,5,1,null,\"+1415\",0]],null]]]"
        assertEquals(GvCallType.Incoming, GoogleVoiceParser.parseCalls(inc).single().type)
        val missed = "[[[\"c.m\",1,[[\"h\",1786409649630,\"+1000\",[\"+1415\"],14,1,null,null,0," +
            "null,null,null,5,1,null,\"+1415\",0]],null]]]"
        assertEquals(GvCallType.Missed, GoogleVoiceParser.parseCalls(missed).single().type)

        assertTrue(GoogleVoiceParser.parseCalls("garbage").isEmpty())
        // Record with no items has no counterparty and is dropped.
        assertTrue(GoogleVoiceParser.parseCalls("[[[\"c.x\",1,[],null]]]").isEmpty())
    }

    @Test
    fun parseVoicemails_filtersNonVoicemailAndReadsTranscript() {
        // type slot 4 = 2 marks voicemail; transcript at slot 6; audio URL at 11; read flag at 13.
        fun item(type: Long, read: Long, words: String?): String {
            val transcript = words?.let {
                "[0.9,[[\"$it\",null,null,0.8]]]"
            } ?: "null"
            // slots: 0 id, 1 ts, 2 own, 3 participants, 4 type, 5 ?, 6 transcript, 7-10 ?,
            // 11 audio, 12 ?, 13 read, 14 ?, 15 counterparty
            return "[\"v1\",1786409649630,\"+1000\",[\"+1415\"],$type,null,$transcript,null,null,null,null," +
                "\"https://voice.google.com/media/a\",null,$read,null,\"+1415\"]"
        }
        val body = "[[[\"c.vm\",1,[${item(2, 1, "hello there")}],null]]]"
        val vm = GoogleVoiceParser.parseVoicemails(body).single()
        assertEquals("c.vm", vm.id)
        assertEquals("+1415", vm.phoneNumber)
        assertEquals("hello there", vm.transcript)
        assertEquals("https://voice.google.com/media/a", vm.audioUrl)
        assertTrue(vm.read)

        // A regular call record (type 14) is not a voicemail.
        val callBody = "[[[\"c.vm\",1,[${item(14, 1, "hello there")}],null]]]"
        assertTrue(GoogleVoiceParser.parseVoicemails(callBody).isEmpty())
        // Unread flag (8) reads as unread; missing transcript is null.
        val unread = "[[[\"c.vm\",1,[${item(2, 8, null)}],null]]]"
        val vmUnread = GoogleVoiceParser.parseVoicemails(unread).single()
        assertFalse(vmUnread.read)
        assertNull(vmUnread.transcript)

        assertTrue(GoogleVoiceParser.parseVoicemails("garbage").isEmpty())
    }

    @Test
    fun parseSipRegisterInfo_returnsPhoneAndOpaqueCreds() {
        val info = GoogleVoiceParser.parseSipRegisterInfo("garbage")
        assertNull(info.phoneNumber)
        assertTrue(info.credentials.isEmpty())

        val body = "[\"+15551234567\",\"short\",\"${"a".repeat(40)}\",\"${"b".repeat(24)}\"]"
        val parsed = GoogleVoiceParser.parseSipRegisterInfo(body)
        assertEquals("+15551234567", parsed.phoneNumber)
        assertEquals(listOf("a".repeat(40), "b".repeat(24)), parsed.credentials)
    }

    @Test
    fun parseCalls_fallsBackAcrossCounterpartySlots() {
        // Item participants: ["+1415"].
        fun item(own: String, parts: String, id: String = "\"h\"") =
            "[$id,1786409649630,\"$own\",[$parts],14,1,null,null,30,null,null,null,5,1,null,\"+1415\",0]"
        // Newest-item participants win.
        val direct = "[[[\"c.d\",1,[${item("+1000", "\"+1415\"")}],null]]]"
        assertEquals("+1415", GoogleVoiceParser.parseCalls(direct).single().phoneNumber)
        // Blank item participants: record slot 4 holds the participant array.
        val viaA = "[[[\"c.a\",1,[${item("+1000", "")}],null,[[\"+1425\"]]]]]"
        assertEquals("+1425", GoogleVoiceParser.parseCalls(viaA).single().phoneNumber)
        // Slot 4 not an array: record slot 7, participant 0.
        val viaB = "[[[\"c.b\",1,[${item("+1000", "")}],null,\"x\",null,null,[\"+1435\"]]]]"
        assertEquals("+1435", GoogleVoiceParser.parseCalls(viaB).single().phoneNumber)
        // No slots at all: opaque c. id is not a phone, so the record drops.
        val opaque = "[[[\"c.zzz\",1,[${item("+1000", "")}],null]]]"
        assertTrue(GoogleVoiceParser.parseCalls(opaque).isEmpty())
        // Digit-only t. id IS a phone and becomes the counterparty.
        val digits = "[[[\"t.4155550111\",1,[${item("+1000", "")}],null]]]"
        assertEquals("4155550111", GoogleVoiceParser.parseThreads(digits).single().phoneNumber)
    }

    @Test
    fun gvModels_holdTheirFields() {
        val msg = com.vayunmathur.communicate.data.googlevoice.GvMessage(
            id = "m1",
            threadId = "t.1",
            phoneNumber = "+1415",
            text = "hi",
            timestampMillis = 42L,
            outgoing = false,
            read = true,
            mediaUrls = listOf("https://x/y.jpg"),
        )
        assertEquals("m1", msg.id)
        assertEquals("t.1", msg.threadId)
        assertTrue(msg.read)
        assertTrue(msg.hasMedia)
        val plain = msg.copy(mediaUrls = emptyList())
        assertTrue(plain.mediaUrls.isEmpty())
        assertEquals(msg, msg.copy())
        val thread = com.vayunmathur.communicate.data.googlevoice.GvThread(
            id = "t.1",
            phoneNumber = "+1415",
            displayName = "Alice",
            snippet = "hi",
            timestampMillis = 42L,
            unreadCount = 2,
            messages = listOf(msg),
        )
        assertEquals("Alice", thread.displayName)
        assertEquals(2, thread.unreadCount)
        assertEquals(thread, thread.copy())
        val call = GoogleVoiceParser.parseCalls(
            "[[[\"c.g\",1,[[\"h\",1786409649630,\"+1000\",[\"+1415\"],14,1,null,null,30," +
                "null,null,null,5,1,null,\"+1415\",0]],null]]]",
        ).single()
        assertEquals("c.g", call.id)
        assertNull(call.displayName)
        assertEquals(1786409649630L, call.timestampMillis)
    }
}
