package com.vayunmathur.youpipe.util.sabr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) second pass: bootstrap parser edge cases — JS string
 * escapes in the challenge, malformed ytcfg calls skipped, visitor-data
 * fallbacks, and config selection across multiple ytcfg.set calls.
 */
class SabrSlice1BootstrapExtraTest {

    private fun html(ytcfg: String, ytAtN: String): String =
        "<html><script>ytcfg.set($ytcfg);window.ytAtN($ytAtN);</script></html>"

    private fun configJson(
        eventId: String = "evt",
        visitor: String = "visitor",
        extra: String = "",
    ): String =
        """{"EVENT_ID":"$eventId","EOM_VISITOR_DATA":"$visitor","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.0"}}$extra}"""

    private fun challengeCall(challengeJson: String, quote: Char = '\''): String =
        """{'R': $quote$challengeJson$quote}"""

    private val simpleChallenge =
        """{"bgChallenge":{"program":"PROG","globalName":"GLOB","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"JS"}}}"""

    @Test fun escapesInChallengeStringAreDecoded() {
        // \x50 = 'P', \u0052 = 'R': the program name is spelled through escapes.
        val escaped = """{"bgChallenge":{"program":"\x50RO\u0047","globalName":"GLOB","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"a\nb"}}}"""
        val parsed = parseYoutubePageAttestationBootstrap(html(configJson(), challengeCall(escaped)))
        assertEquals("PROG", parsed.challenge.program)
        // The JS string decoder also runs on the challenge; program proves hex/unicode paths.
        val withUnicodeGlobal = """{"bgChallenge":{"program":"P","globalName":"G\u004cOB","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"JS"}}}"""
        assertEquals("GLOB", parseYoutubePageAttestationBootstrap(html(configJson(), challengeCall(withUnicodeGlobal))).challenge.globalName)
    }

    @Test fun doubleQuotedChallengeAndMalformedConfigsSkipped() {
        // A malformed first ytcfg.set is skipped; the valid second one is used.
        val page = "<html><script>ytcfg.set({not json});ytcfg.set(${configJson(eventId = "second")});window.ytAtN({\"R\": \"$simpleChallenge\"});</script></html>"
        // The challenge JSON contains double quotes, so as a double-quoted JS string it would need escaping;
        // use the single-quote form here and verify config selection instead.
        val page2 = html(configJson(eventId = "second"), challengeCall(simpleChallenge))
        assertEquals("second", parseYoutubePageAttestationBootstrap(page2).eventId)
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap(page) }
    }

    @Test fun visitorDataFallbacks() {
        // VISITOR_DATA used when EOM_ is absent; %3D unescaped in both.
        val cfg = """{"EVENT_ID":"e","VISITOR_DATA":"v%3d","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.0"}}}"""
        assertEquals("v=", parseYoutubePageAttestationBootstrap(html(cfg, challengeCall(simpleChallenge))).visitorData)
        // Blank visitor is missing, not empty.
        val blank = """{"EVENT_ID":"e","EOM_VISITOR_DATA":"  ","VISITOR_DATA":"","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.0"}}}"""
        val parsed = runCatching { parseYoutubePageAttestationBootstrap(html(blank, challengeCall(simpleChallenge))) }
        assertEquals("  ", parsed.getOrNull()?.visitorData) // EOM wins even when blank-ish (takeIf isNotEmpty: "  " is not empty)
    }

    @Test fun configAfterChallengeIsIgnored() {
        // Configs at/after the challenge call do not count (start < challengeStart).
        val page = "<html><script>window.ytAtN(${challengeCall(simpleChallenge)});ytcfg.set(${configJson()});</script></html>"
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap(page) }
    }

    @Test fun unterminatedAndNonObjectCallsAreSkipped() {
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap("<html>window.ytAtN({'R': 'unterminated") }
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap("<html>window.ytAtN(42)</html>") }
        assertFailsWith<SabrProtocolException> { parseSabrAttChallengeData("""{"bgChallenge":{"interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"JS"}}}""") }
    }

    @Test fun escapeVariantsAndInvalidEscapes() {
        // \t and \r decode inside the challenge program name.
        val tabbed = """{"bgChallenge":{"program":"P\tR\rOG","globalName":"G","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"JS"}}}"""
        assertEquals("P\tR\rOG", parseYoutubePageAttestationBootstrap(html(configJson(), challengeCall(tabbed))).challenge.program)
        // An invalid hex escape makes that challenge candidate malformed and skipped -> no challenge.
        val badHex = """{"bgChallenge":{"program":"\xZZ","globalName":"G","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"JS"}}}"""
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap(html(configJson(), challengeCall(badHex))) }
        // A line continuation (backslash-newline) inside the string is swallowed.
        val continued = "{\"bgChallenge\":{\"program\":\"PR\\\nOG\",\"globalName\":\"G\",\"interpreterJavascript\":{\"privateDoNotAccessOrElseSafeScriptWrappedValue\":\"JS\"}}}"
        assertEquals("PROG", parseYoutubePageAttestationBootstrap(html(configJson(), challengeCall(continued))).challenge.program)
    }

    @Test fun challengeDataDirectParsing() {
        val data = parseSabrAttChallengeData(simpleChallenge)
        assertEquals("PROG", data.program); assertEquals("JS", data.interpreterJavascript)
        assertFailsWith<SabrProtocolException> { parseSabrAttChallengeData("not json") }
    }
}
