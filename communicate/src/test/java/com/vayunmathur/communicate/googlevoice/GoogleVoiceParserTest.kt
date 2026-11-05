package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GvCallType
import com.vayunmathur.communicate.data.googlevoice.GvFolder
import com.vayunmathur.communicate.data.googlevoice.buildBatchUpdateBody
import com.vayunmathur.communicate.data.googlevoice.buildListBody
import com.vayunmathur.communicate.data.googlevoice.buildSearchBody
import com.vayunmathur.communicate.data.googlevoice.buildSendSmsBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleVoiceParserTest {

    @Test
    fun buildListBody_matchesDocumentedShape() {
        assertEquals("[2,20,15,null,null,[null,1,1,1]]", GoogleVoiceParser.buildListBody(GvFolder.Inbox))
    }

    @Test
    fun buildSearchBody_matchesDocumentedShape() {
        assertEquals(
            "[\"gv har\",200,null,null,null,[null,1,1,1]]",
            GoogleVoiceParser.buildSearchBody("gv har"),
        )
    }

    @Test
    fun buildBatchUpdate_readAndArchiveUseCorrectSlots() {
        // Read toggles index 3 (4-element key); archive toggles index 2 (3-element key).
        // Mixing these up archives a thread when you meant to mark it read.
        assertEquals(
            "[[[[\"t.abc\",null,null,1],[null,null,null,1],1]]]",
            GoogleVoiceParser.buildBatchUpdateBody("t.abc", GoogleVoiceParser.ThreadAction.MarkRead),
        )
        assertEquals(
            "[[[[\"t.abc\",null,1],[null,null,1],1]]]",
            GoogleVoiceParser.buildBatchUpdateBody("t.abc", GoogleVoiceParser.ThreadAction.Archive),
        )
    }

    @Test
    fun buildSendSms_newThreadUsesRecipientSlot() {
        // [null,null,null,null, text, threadId|null, [recipient]|null, null, [txn], null]
        val body = GoogleVoiceParser.buildSendSmsBody("+12134774209", "hi", threadRemoteId = null, clientTxnId = 42L)
        assertEquals("[null,null,null,null,\"hi\",null,[\"+12134774209\"],null,[42],null]", body)
    }

    @Test
    fun buildSendSms_existingThreadUsesThreadSlot() {
        val body = GoogleVoiceParser.buildSendSmsBody(
            "+12134774209",
            "hi",
            threadRemoteId = "t.+12134774209",
            clientTxnId = 42L)
        assertEquals("[null,null,null,null,\"hi\",\"t.+12134774209\",null,null,[42],null]", body)
    }

    @Test
    fun normalizeTimestamp_handlesMicrosMillisSeconds() {
        assertEquals(1_691_800_000_000L, GoogleVoiceParser.normalizeTimestampMillis(1_691_800_000_000_000L)) // µs
        assertEquals(1_691_800_000_000L, GoogleVoiceParser.normalizeTimestampMillis(1_691_800_000_000L)) // ms
        assertEquals(1_691_800_000_000L, GoogleVoiceParser.normalizeTimestampMillis(1_691_800_000L)) // s
        assertEquals(0L, GoogleVoiceParser.normalizeTimestampMillis(0L))
    }

    @Test
    fun looksLikePhone_distinguishesNumbers() {
        assertTrue(GoogleVoiceParser.looksLikePhone("+15551234567"))
        assertTrue(GoogleVoiceParser.looksLikePhone("5551234567"))
        assertFalse(GoogleVoiceParser.looksLikePhone("hello"))
        assertFalse(GoogleVoiceParser.looksLikePhone("t.abc123"))
    }

    @Test
    fun parseThreads_extractsCounterpartyFromIdAndBody() {
        // Real api2thread/list shape: [[ record ]]; record = ["t.<counterparty>",state,[items],...]
        // item = [id, ts, myNumber, [counterparty,...], type, read, null,null,null, body@9, ...]
        val body = "[[[\"t.+14152124034\",1,[[\"h1\",1786409649630,\"+12133945548\"," +
            "[\"+14152124034\",\"+14152124034\",null,null,null,null,0],10,1,null,null,null," +
            "\"Hello there\",null,null,5,1,null,\"+14152124034\",0]],null,[[\"+14152124034\"]]]]]"
        val threads = GoogleVoiceParser.parseThreads(body)
        assertEquals(1, threads.size)
        val t = threads.first()
        assertEquals("t.+14152124034", t.id)
        assertEquals("+14152124034", t.phoneNumber) // counterparty, NOT the account number
        assertEquals("Hello there", t.snippet)
        assertEquals(1_786_409_649_630L, t.timestampMillis)
        assertEquals(1, t.messages.size)
        assertFalse(t.messages.first().outgoing) // inbound: own(2) != counterparty
    }

    @Test
    fun parseThreadMessages_readsFromListRecord() {
        val body = "[[[\"t.+14152124034\",1,[[\"h1\",1786409649630,\"+12133945548\"," +
            "[\"+14152124034\",\"+14152124034\",null,null,null,null,0],10,1,null,null,null," +
            "\"Hello there\",null,null,5,1,null,\"+14152124034\",0]],null,[[\"+14152124034\"]]]]]"
        val msgs = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034")
        assertEquals(1, msgs.size)
        assertEquals("Hello there", msgs.first().text)
        assertEquals("+14152124034", msgs.first().phoneNumber)
    }

    @Test
    fun parseCalls_usesCounterpartyNotAccountAndDuration() {
        // Call record: id is "c.<opaque>"; counterparty in item participants; duration@8.
        val body = "[[[\"c.OPAQUEID\",0,[[\"OPAQUEID\",1786504879641,\"+12133945548\"," +
            "[\"+12134774209\",\"+12134774209\",null,null,null,null,0],1,1,null,null,24,\"\"," +
            "null,null,1,0,null,\"+12134774209\",0]],null,[[\"+12134774209\"]]]]]"
        val calls = GoogleVoiceParser.parseCalls(body)
        assertEquals(1, calls.size)
        val c = calls.first()
        assertEquals("c.OPAQUEID", c.id)
        assertEquals("+12134774209", c.phoneNumber) // counterparty, NOT +12133945548
        assertEquals(24L, c.durationSeconds)
        assertEquals(GvCallType.Incoming, c.type)
    }

    @Test
    fun parseThreadMessages_marksOutboundByType11() {
        // Two items in one thread: type 10 inbound, type 11 outbound.
        val body = "[[[\"t.+14152124034\",1,[" +
            "[\"m1\",1786000000000,\"+12133945548\",[\"+14152124034\"],10,1,null,null,null,\"in\",null,null,5,1,null,\"+14152124034\",0]," +
            "[\"m2\",1786000100000,\"+12133945548\",[\"+14152124034\"],11,1,null,null,null,\"out\",null,null,5,1,null,\"+14152124034\",0]" +
            "],null,[[\"+14152124034\"]]]]]"
        val msgs = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034")
        assertEquals(2, msgs.size)
        val inbound = msgs.first { it.text == "in" }
        val outbound = msgs.first { it.text == "out" }
        assertFalse(inbound.outgoing)
        assertTrue(outbound.outgoing)
    }

    @Test
    fun parseThreadMessages_keepsTextOnlyMessagesWithoutMedia() {
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],10,1,null,null,null,\"plain text\",null,null,5,1,null,\"+14152124034\",0]]," +
            "null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals("plain text", message.text)
        assertEquals(emptyList(), message.mediaUrls)
    }

    @Test
    fun parseThreadMessages_extractsGoogleHostedMediaUrls() {
        val media = "https://lh3.googleusercontent.com/voice-mms/photo.jpg=s1024"
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],10,1,null,null,null,\"photo\",null,[null,\"$media\"],5,1,null,\"+14152124034\",0]]," +
            "null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals(listOf(media), message.mediaUrls)
        assertTrue(message.hasMedia)
    }

    @Test
    fun parseThreadMessages_blanksOutboundMediaOnlyStatusLabel() {
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],11,1,null,null,null,\"MMS Sent\",null,null,5,1,null,\"+14152124034\"," +
            "[[null,\"image/jpeg\",\"m1-1\"]]]],null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals("", message.text)
        assertEquals(emptyList(), message.mediaUrls)
        assertTrue(message.hasMedia)
        assertTrue(message.outgoing)
    }

    @Test
    fun parseThreadMessages_blanksInboundMediaOnlyStatusLabel() {
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],10,1,null,null,null,\"mMs ReCeIvEd\",null,null,5,1,null,\"+14152124034\"," +
            "[[null,\"image/jpeg\",\"m1-1\"]]]],null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals("", message.text)
        assertEquals(emptyList(), message.mediaUrls)
        assertTrue(message.hasMedia)
        assertFalse(message.outgoing)
    }

    @Test
    fun parseThreadMessages_preservesCaptionTextWithMediaMetadata() {
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],10,1,null,null,null,\"real caption\",null,null,5,1,null,\"+14152124034\"," +
            "[[null,\"image/jpeg\",\"m1-1\"]]]],null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals("real caption", message.text)
        assertTrue(message.hasMedia)
    }

    @Test
    fun parseThreadMessages_ignoresMalformedSlot16Metadata() {
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],10,1,null,null,null,\"MMS Received\",null,null,5,1,null,\"+14152124034\"," +
            "[null,123,{}]]],null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals("MMS Received", message.text)
        assertFalse(message.hasMedia)
    }

    @Test
    fun parseThreadMessages_ignoresMalformedNullAndNonMediaShapes() {
        val body = "[[[\"t.+14152124034\",1,[[\"m1\",1786000000000,\"+12133945548\"," +
            "[\"+14152124034\"],10,1,null,null,null,\"https://example.com/not-media\",null," +
            "[null,123,null,[\"https://accounts.google.com/signin\"]],5,1,null,\"+14152124034\",0]]," +
            "null,[[\"+14152124034\"]]]]]"
        val message = GoogleVoiceParser.parseThreadMessages(body, "t.+14152124034").single()
        assertEquals("https://example.com/not-media", message.text)
        assertEquals(emptyList(), message.mediaUrls)
    }

    @Test
    fun parseAccount_findsPrimaryNumber() {
        val body = "[\"+15550001111\",null,[\"settings\"]]"
        assertEquals("+15550001111", GoogleVoiceParser.parseAccount(body).phoneNumber)
    }

    // Voicemail records (folder 4): type slot 2 marks voicemail (calls use 14);
    // transcript word list at slot 6; playable media URL at slot 11; duration at
    // slot 8; read flag at slot 13 (1 read, 8 unread). Shape from live capture
    // (record-level slots past the items array omitted — the parser only reads
    // the id at 0 and the items at 2).
    private fun voicemailBody(
        id: String = "c.VMID1",
        transcript: String = "[0.96,[[\"Hey\",null,null,0.96],[\"there\",null,null,0.95]]]",
        audioUrl: String? = "\"https://www.google.com/voice/media/svm/ACCOUNT/TOKEN\"",
        type: Int = 2,
        readFlag: Int = 1,
    ): String {
        val audio = audioUrl ?: "null"
        return "[[[\"$id\",1,[[\"VMID1\",1792622182651,\"+12133945548\"," +
            "[\"+15550001111\",\"+15550001111\",null,null,null,null,0],$type,1,$transcript," +
            "null,44,\"\",null,$audio,3,$readFlag,null,\"+15550001111\",0]]]]]"
    }

    @Test
    fun parseVoicemails_mapsRecordSlots() {
        val vms = GoogleVoiceParser.parseVoicemails(voicemailBody())
        assertEquals(1, vms.size)
        val vm = vms.single()
        assertEquals("c.VMID1", vm.id)
        assertEquals("+15550001111", vm.phoneNumber)
        assertEquals(1792622182651L, vm.timestampMillis)
        assertEquals(44L, vm.durationSeconds)
        assertEquals("Hey there", vm.transcript)
        assertEquals("https://www.google.com/voice/media/svm/ACCOUNT/TOKEN", vm.audioUrl)
        assertTrue(vm.read)
    }

    @Test
    fun parseVoicemails_marksUnreadFlag8() {
        val vm = GoogleVoiceParser.parseVoicemails(voicemailBody(readFlag = 8)).single()
        assertFalse(vm.read)
    }

    @Test
    fun parseVoicemails_nullTranscriptAndAudio() {
        val vm = GoogleVoiceParser.parseVoicemails(
            voicemailBody(transcript = "null", audioUrl = null),
        ).single()
        assertEquals(null, vm.transcript)
        assertEquals(null, vm.audioUrl)
    }

    @Test
    fun parseVoicemails_skipsCallRecords() {
        // Folder-3 call shape in a folder-4 body: type 14, no transcript/audio.
        val vms = GoogleVoiceParser.parseVoicemails(voicemailBody(id = "c.CALL1", type = 14))
        assertTrue(vms.isEmpty())
    }

    @Test
    fun parseVoicemails_skipsEmptyItems() {
        val vms = GoogleVoiceParser.parseVoicemails("[[[\"c.EMPTY\",1,[]]]]")
        assertTrue(vms.isEmpty())
    }
}
