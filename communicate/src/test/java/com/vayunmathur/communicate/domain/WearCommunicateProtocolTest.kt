package com.vayunmathur.communicate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearCommunicateProtocolTest {

    private fun thread(
        key: String = "WhatsApp:abc",
        snippet: String = "Hi there",
        timestamp: Long = 900L,
        disappearing: Boolean = false,
    ) = WearThread(
        key = key, threadId = 7L, line = WearCommLine.WhatsApp, address = "+1555",
        displayName = "Alice", snippet = snippet, timestampMillis = timestamp,
        unreadCount = 2, disappearing = disappearing,
    )

    private fun message(id: String, ts: Long, body: String = "msg $id", disappearing: Boolean = false) =
        WearMessage(
            messageKey = id, threadKey = "WhatsApp:abc", body = body,
            timestampMillis = ts, disappearing = disappearing,
        )

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearCommunicateSnapshot(
            generatedAtMillis = 1_000L,
            threads = listOf(thread()),
        )
        assertEquals(
            """{"generatedAtMillis":1000,"threads":[{"key":"WhatsApp:abc","threadId":7,""" +
                """"line":"WhatsApp","address":"+1555","displayName":"Alice","snippet":"Hi there",""" +
                """"timestampMillis":900,"unreadCount":2}]}""",
            WearCommunicateProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun goldenOpJson() {
        val op = WearCommOp(
            opId = "op1", kind = WearOpKind.QuickReply, threadKey = "WhatsApp:abc",
            line = WearCommLine.WhatsApp, body = "OK",
        )
        assertEquals(
            """{"opId":"op1","kind":"QuickReply","threadKey":"WhatsApp:abc","line":"WhatsApp","body":"OK"}""",
            WearCommunicateProtocol.json.encodeToString(op),
        )
    }

    @Test
    fun snapshotRoundTrip() {
        val snapshot = buildWearCommunicateSnapshot(
            threads = listOf(thread()),
            calls = listOf(
                WearCallEntry(key = "Sim:1", phoneNumber = "+1555", type = "Missed", timestampMillis = 5L),
            ),
            voicemails = listOf(
                WearVoicemail(key = "Sim:2", phoneNumber = "+1555", timestampMillis = 4L, isRead = false),
            ),
            generatedAtMillis = 10L,
        )
        assertEquals(
            snapshot,
            WearCommunicateProtocol.json.decodeFromString<WearCommunicateSnapshot>(
                WearCommunicateProtocol.json.encodeToString(snapshot),
            ),
        )
        assertEquals(1, snapshot.voicemailUnreadCount)
    }

    @Test
    fun buildCapsCountsFieldsAndBytes() {
        val threads = (1..(WearCommunicateProtocol.MAX_THREADS + 20)).map {
            thread(key = "Sim:$it", snippet = "s".repeat(500), timestamp = it.toLong())
        }
        val snapshot = buildWearCommunicateSnapshot(threads, emptyList(), emptyList(), 0L)
        assertTrue(snapshot.notesSize() <= WearCommunicateProtocol.MAX_THREADS)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.threads.all {
            it.snippet.length == WearCommunicateProtocol.MAX_SNIPPET_CHARS
        })
        val bytes = WearCommunicateProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        assertTrue(bytes <= WearCommunicateProtocol.MAX_JSON_BYTES, "snapshot $bytes over cap")
        assertEquals("Sim:${WearCommunicateProtocol.MAX_THREADS + 20}", snapshot.threads.first().key)
    }

    private fun WearCommunicateSnapshot.notesSize() = threads.size

    @Test
    fun disappearingThreadExcludesSnippetEverywhere() {
        val snapshot = buildWearCommunicateSnapshot(
            threads = listOf(thread(snippet = "secret text", disappearing = true)),
            calls = emptyList(), voicemails = emptyList(), generatedAtMillis = 0L,
        )
        assertEquals("", snapshot.threads.single().snippet)
        assertTrue(snapshot.threads.single().contentExcluded)
        val alert = buildWearNewMessageAlert(snapshot.threads.single(), 1_000L)
        assertEquals("", alert.snippet)
        assertTrue(alert.contentExcluded)
        val encoded = WearCommunicateProtocol.json.encodeToString(snapshot)
        assertFalse(encoded.contains("secret text"), "disappearing snippet leaked: $encoded")
    }

    @Test
    fun disappearingMessageExcludesBodyAndMedia() {
        val page = buildWearThreadPage(
            threadKey = "WhatsApp:abc",
            messages = listOf(
                message("m1", 100L, body = "secret body", disappearing = true)
                    .copy(mediaLabel = "Photo"),
            ),
        )
        val only = page.messages.single()
        assertEquals("", only.body)
        assertEquals(null, only.mediaLabel)
        assertTrue(only.contentExcluded)
        assertFalse(WearCommunicateProtocol.json.encodeToString(page).contains("secret body"))
    }

    @Test
    fun pagingLatestThenOlderWithCursor() {
        val messages = (1..45).map { message("m$it", it * 10L) }
        val latest = buildWearThreadPage("WhatsApp:abc", messages)
        assertEquals(WearCommunicateProtocol.MAX_PAGE_MESSAGES, latest.messages.size)
        assertEquals("m26", latest.messages.first().messageKey)
        assertEquals("m45", latest.messages.last().messageKey)
        assertTrue(latest.hasMore)
        val older = buildWearThreadPage(
            "WhatsApp:abc", messages,
            beforeTimestampMillis = latest.oldestTimestampMillis,
        )
        assertEquals("m6", older.messages.first().messageKey)
        assertEquals("m25", older.messages.last().messageKey)
        assertTrue(older.hasMore)
        val oldest = buildWearThreadPage(
            "WhatsApp:abc", messages,
            beforeTimestampMillis = older.oldestTimestampMillis,
        )
        assertEquals(5, oldest.messages.size)
        assertFalse(oldest.hasMore)
    }

    @Test
    fun mergeDedupesAndCaps() {
        val first = buildWearThreadPage(
            "WhatsApp:abc", (1..20).map { message("m$it", it.toLong()) },
        )
        val merged = mergeWearThreadPage(first.messages, first)
        assertEquals(20, merged.size)
        val big = mergeWearThreadPage(
            merged, buildWearThreadPage("WhatsApp:abc", (21..200).map { message("m$it", it.toLong()) }),
        )
        assertTrue(big.size <= WearCommunicateProtocol.MAX_CACHED_MESSAGES_PER_THREAD)
        assertEquals("m200", big.last().messageKey)
    }

    @Test
    fun replyValidationAndResultHandling() {
        val good = WearCommOp(
            opId = "a", kind = WearOpKind.QuickReply, threadKey = "Sim:1", body = "  On my way  ",
        )
        assertEquals("On my way", validWearReply(good))
        assertEquals(null, validWearReply(good.copy(body = "   ")))
        assertEquals(null, validWearReply(good.copy(kind = WearOpKind.MarkRead)))
        val pending = listOf(good, good.copy(opId = "b"))
        assertEquals(
            listOf(pending[1]),
            pendingAfterResult(pending, WearOpResult("a", WearOpKind.QuickReply, success = true)),
        )
        assertEquals(
            pending,
            pendingAfterResult(pending, WearOpResult("a", WearOpKind.QuickReply, success = false)),
        )
    }

    @Test
    fun threadKeyPrefersRemoteId() {
        assertEquals("Signal:conv1", wearThreadKey(WearCommLine.Signal, "conv1", 42L))
        assertEquals("Sim:42", wearThreadKey(WearCommLine.Sim, null, 42L))
        assertEquals("Sim:42", wearThreadKey(WearCommLine.Sim, "", 42L))
    }
}
