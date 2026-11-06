package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.ContextInfoResult
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.convertWAFormattingToHtml
import com.vayunmathur.communicate.data.whatsapp.extractContextInfo
import com.vayunmathur.communicate.data.whatsapp.formatDisappearingTimer
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): message formatting + disappearing-timer copy for the whatsapp/ package.
 * Pure JVM (protobuf-java + regex only).
 */
class W5Slice1FormatTextTest {

    // ---- formatDisappearingTimer ----

    @Test
    fun timer_secondsBranch() {
        assertEquals("45 seconds", WhatsAppProtocol.formatDisappearingTimer(45))
        assertEquals("0 seconds", WhatsAppProtocol.formatDisappearingTimer(0))
    }

    @Test
    fun timer_minutesAndHoursBranches() {
        assertEquals("5 minutes", WhatsAppProtocol.formatDisappearingTimer(300))
        assertEquals("2 hours", WhatsAppProtocol.formatDisappearingTimer(7200))
    }

    @Test
    fun timer_daysWeeksAnd90DaysBranches() {
        assertEquals("3 days", WhatsAppProtocol.formatDisappearingTimer(3 * 86400))
        assertEquals("7 days", WhatsAppProtocol.formatDisappearingTimer(7 * 86400))
        assertEquals("90 days", WhatsAppProtocol.formatDisappearingTimer(90 * 86400))
        assertEquals("90 days", WhatsAppProtocol.formatDisappearingTimer(200 * 86400))
    }

    @Test
    fun timer_boundaries() {
        assertEquals("1 minutes", WhatsAppProtocol.formatDisappearingTimer(60))
        assertEquals("59 seconds", WhatsAppProtocol.formatDisappearingTimer(59))
        assertEquals("1 hours", WhatsAppProtocol.formatDisappearingTimer(3600))
        assertEquals("1 days", WhatsAppProtocol.formatDisappearingTimer(86400))
    }

    // ---- extractContextInfo ----

    private fun ctxMsg(build: WhatsAppE2EProto.Message.Builder.() -> Unit): WhatsAppE2EProto.Message =
        WhatsAppE2EProto.Message.newBuilder().apply(build).build()

    @Test
    fun contextInfo_emptyMessage_returnsDefaults() {
        val result = WhatsAppProtocol.extractContextInfo(
            WhatsAppE2EProto.Message.newBuilder().setConversation("hi").build(),
        )
        assertEquals(ContextInfoResult(), result)
        assertFalse(result.isForwarded)
    }

    @Test
    fun contextInfo_extendedText_readsForwardingReplyAndMentions() {
        val ctx = WhatsAppE2EProto.ContextInfo.newBuilder()
            .setIsForwarded(true)
            .setForwardingScore(5)
            .setStanzaId("REPLY1")
            .addMentionedJid("111@s.whatsapp.net")
            .build()
        val msg = ctxMsg {
            setExtendedTextMessage(
                WhatsAppE2EProto.ExtendedTextMessage.newBuilder().setText("fwd").setContextInfo(ctx),
            )
        }
        val result = WhatsAppProtocol.extractContextInfo(msg)
        assertTrue(result.isForwarded)
        assertEquals(5, result.forwardingScore)
        assertEquals("REPLY1", result.replyToId)
        assertEquals(listOf("111@s.whatsapp.net"), result.mentionedJids)
    }

    @Test
    fun contextInfo_blankStanzaId_becomesNull() {
        val ctx = WhatsAppE2EProto.ContextInfo.newBuilder().setStanzaId("").build()
        val msg = ctxMsg {
            setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setContextInfo(ctx))
        }
        assertEquals(null, WhatsAppProtocol.extractContextInfo(msg).replyToId)
    }

    @Test
    fun contextInfo_readsFromEachMediaType() {
        val ctx = WhatsAppE2EProto.ContextInfo.newBuilder().setStanzaId("X").build()
        val variants = listOf(
            ctxMsg { setVideoMessage(WhatsAppE2EProto.VideoMessage.newBuilder().setContextInfo(ctx)) },
            ctxMsg { setAudioMessage(WhatsAppE2EProto.AudioMessage.newBuilder().setContextInfo(ctx)) },
            ctxMsg { setDocumentMessage(WhatsAppE2EProto.DocumentMessage.newBuilder().setContextInfo(ctx)) },
            ctxMsg { setStickerMessage(WhatsAppE2EProto.StickerMessage.newBuilder().setContextInfo(ctx)) },
            ctxMsg { setLocationMessage(WhatsAppE2EProto.LocationMessage.newBuilder().setContextInfo(ctx)) },
            ctxMsg { setContactMessage(WhatsAppE2EProto.ContactMessage.newBuilder().setContextInfo(ctx)) },
        )
        for (m in variants) {
            assertEquals("X", WhatsAppProtocol.extractContextInfo(m).replyToId)
        }
    }

    // ---- convertWAFormattingToHtml ----

    @Test
    fun html_boldItalicStrikethroughAndCode() {
        assertEquals(
            "<b>bold</b>",
            WhatsAppProtocol.convertWAFormattingToHtml("*bold*"),
        )
        assertEquals(
            "<i>it</i>",
            WhatsAppProtocol.convertWAFormattingToHtml("_it_"),
        )
        assertEquals(
            "<s>strike</s>",
            WhatsAppProtocol.convertWAFormattingToHtml("~strike~"),
        )
        assertEquals(
            "<code>code</code>",
            WhatsAppProtocol.convertWAFormattingToHtml("`code`"),
        )
    }

    @Test
    fun html_fencedCodeBlock_multilineUsesPre() {
        val out = WhatsAppProtocol.convertWAFormattingToHtml("before ```a\nb``` after")
        assertTrue(out.contains("<pre><code>a\nb</code></pre>"), out)
        assertTrue(out.startsWith("before "), out)
        assertTrue(out.endsWith(" after"), out)
    }

    @Test
    fun html_fencedCodeBlock_singleLineUsesInlineCode() {
        assertEquals(
            "<code>hi</code>",
            WhatsAppProtocol.convertWAFormattingToHtml("```hi```"),
        )
    }

    @Test
    fun html_unclosedFence_isInlineFormatted() {
        assertEquals("a ```b", WhatsAppProtocol.convertWAFormattingToHtml("a ```b").replace("<br>", "\n"))
    }

    @Test
    fun html_escapesHtmlInTextAndCode() {
        assertEquals("&lt;b&gt;", WhatsAppProtocol.convertWAFormattingToHtml("<b>"))
        assertEquals(
            "<code>a&lt;b&gt;&amp;c</code>",
            WhatsAppProtocol.convertWAFormattingToHtml("```a<b>&c```"),
        )
    }

    @Test
    fun html_blockquoteAndLists() {
        val quote = WhatsAppProtocol.convertWAFormattingToHtml("> hello\nworld")
        assertEquals("<blockquote>hello</blockquote>\nworld", quote.replace("<br>", "\n"))
        val ol = WhatsAppProtocol.convertWAFormattingToHtml("1. one\n2. two")
        assertTrue(ol.contains("<ol start=\"1\">"), ol)
        assertTrue(ol.contains("<li value=\"1\">one</li>"), ol)
        assertTrue(ol.contains("<li value=\"2\">two</li>"), ol)
        val ul = WhatsAppProtocol.convertWAFormattingToHtml("* a\n* b")
        assertTrue(ul.contains("<ul>"), ul)
        assertTrue(ul.contains("<li>a</li>"), ul)
    }

    @Test
    fun html_inlineUrlAndNewlines() {
        assertEquals(
            "<a href=\"https://e.com\">click</a>",
            WhatsAppProtocol.convertWAFormattingToHtml("[click](https://e.com)"),
        )
        assertEquals("a<br>b", WhatsAppProtocol.convertWAFormattingToHtml("a\nb"))
    }
}
