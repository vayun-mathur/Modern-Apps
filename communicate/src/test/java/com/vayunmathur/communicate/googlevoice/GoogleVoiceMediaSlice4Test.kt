package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.hasMediaMetadataIn
import com.vayunmathur.communicate.data.googlevoice.isMmsStatusLabel
import com.vayunmathur.communicate.data.googlevoice.looksLikeAttachmentId
import com.vayunmathur.communicate.data.googlevoice.looksLikeMediaUrl
import com.vayunmathur.communicate.data.googlevoice.looksLikeMimeType
import com.vayunmathur.communicate.data.googlevoice.recordId
import com.vayunmathur.communicate.data.googlevoice.transcriptText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the Google Voice media/metadata helpers
 * (`GoogleVoiceMedia.kt`).
 *
 * Pure JVM: pins MMS-label suppression, media-URL detection, attachment-id
 * recognition and transcript extraction — the heuristics that decide whether a
 * message renders text, an image, or a voicemail transcript.
 */
class GoogleVoiceMediaSlice4Test {

    private fun item(vararg slots: Any?): JsonArray = buildJsonArray {
        for (s in slots) when (s) {
            null -> add(JsonNull)
            is String -> add(JsonPrimitive(s))
            is Long -> add(JsonPrimitive(s))
            is Int -> add(JsonPrimitive(s))
        }
    }

    /** 17-slot item with body at 9 and counterparty at 15, like the wire. */
    private fun messageItem(body: String, extra: Map<Int, Any?> = emptyMap()): JsonArray {
        val slots = MutableList<Any?>(17) { null }
        slots[GoogleVoiceParser.ITEM_ID] = "m1"
        slots[GoogleVoiceParser.ITEM_TS] = 1_786_409_649_630L
        slots[GoogleVoiceParser.ITEM_OWN] = "+1000"
        slots[GoogleVoiceParser.ITEM_BODY] = body
        slots[GoogleVoiceParser.ITEM_COUNTERPARTY] = "+1415"
        for ((i, v) in extra) slots[i] = v
        return item(*slots.toTypedArray())
    }

    @Test
    fun isMmsStatusLabel_matchesOnlyTheCarrierLabels() {
        assertTrue("mms sent".isMmsStatusLabel())
        assertTrue("MMS Received".isMmsStatusLabel())
        assertFalse("hello".isMmsStatusLabel())
        assertFalse("mms".isMmsStatusLabel())
        assertFalse("".isMmsStatusLabel())
    }

    @Test
    fun looksLikeMimeType_matchesMediaPrefixes() {
        assertTrue("image/jpeg".looksLikeMimeType())
        assertTrue("Video/MP4".looksLikeMimeType())
        assertTrue("audio/ogg".looksLikeMimeType())
        assertFalse("text/plain".looksLikeMimeType())
        assertFalse("application/json".looksLikeMimeType())
    }

    @Test
    fun looksLikeAttachmentId_matchesDashedNumericSuffix() {
        assertTrue("abc_12-345".looksLikeAttachmentId())
        assertTrue("a.b-1".looksLikeAttachmentId())
        assertFalse("hello".looksLikeAttachmentId())
        assertFalse("+1555".looksLikeAttachmentId())
    }

    @Test
    fun looksLikeMediaUrl_requiresGoogleHostAndMediaHint() {
        assertTrue(looksLikeMediaUrl("https://lh3.google.com/media/abc=s0"))
        assertTrue(looksLikeMediaUrl("https://voice.google.com/media/x.jpg"))
        assertFalse(looksLikeMediaUrl("https://example.com/mms/x"))
        assertFalse(looksLikeMediaUrl("https://example.com/image.jpg"))
        assertFalse(looksLikeMediaUrl("http://lh3.google.com/media/abc=s0"))
        assertFalse(looksLikeMediaUrl("https://lh3.google.com/other"))
    }

    @Test
    fun hasMediaMetadataIn_detectsMimeAndAttachmentIds() {
        assertTrue(hasMediaMetadataIn(buildJsonArray { add(JsonPrimitive("image/png")) }))
        assertTrue(hasMediaMetadataIn(buildJsonArray { add(JsonPrimitive("x-99")) }))
        assertFalse(hasMediaMetadataIn(buildJsonArray { add(JsonPrimitive("hello")) }))
        assertFalse(hasMediaMetadataIn(JsonNull))
    }

    @Test
    fun recordId_readsTheLeadingString() {
        assertEquals("t.1", recordId(buildJsonArray { add(JsonPrimitive("t.1")) }))
        assertNull(recordId(buildJsonArray { add(JsonPrimitive(7)) }))
        assertNull(recordId(buildJsonArray {}))
    }

    @Test
    fun transcriptText_joinsWordsAndIgnoresBlanks() {
        fun block(words: List<String?>): JsonArray = buildJsonArray {
            add(JsonPrimitive(0.9))
            add(buildJsonArray {
                for (w in words) {
                    if (w == null) add(JsonNull)
                    else add(buildJsonArray {
                        add(JsonPrimitive(w)); add(JsonNull); add(JsonNull); add(JsonPrimitive(0.8))
                    })
                }
            })
        }
        val slots = MutableList<Any?>(17) { null }
        slots[GoogleVoiceParser.ITEM_TRANSCRIPT] = "__BLOCK__"
        // Splice the real block in (builders cannot nest raw arrays via Any?).
        val base = messageItem("x").toMutableList()
        base[GoogleVoiceParser.ITEM_TRANSCRIPT] = block(listOf("hello", "", "there"))
        val joined = JsonArray(base)
        assertEquals("hello there", joined.transcriptText())
        assertNull(messageItem("x").transcriptText())
    }
}
