package com.vayunmathur.youpipe.platform

import com.vayunmathur.youpipe.network.sabr.YoutubePoTokenBinding
import com.vayunmathur.youpipe.network.sabr.parseSabrAttChallengeData
import com.vayunmathur.youpipe.network.sabr.parseYoutubePageAttestationBootstrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Offline regression tests for the home-page attestation bootstrap (#565).
 *
 * The fixtures reproduce the shape of `https://www.youtube.com` HTML that matters here:
 * `ytcfg.set({...})` configs (visitor data, client version, EVENT_ID, experiment flags) and
 * the `window.ytAtN({...})` initial attestation challenge. Minting against this page-bound
 * challenge is what makes the server accept SABR PO tokens; the old `att/get` UNBOUND flow
 * left every response attestation-pending.
 */
class AttestationBootstrapTest {

    private fun homeHtml(
        visitorData: String = "CgtWMuc2hlbGxvIBD bundle",
        eventId: String = "EVENT123",
        flags: String = "html5_generate_content_po_token=true&other_flag=false",
        program: String = "PROG",
        globalName: String = "GLOB",
        interpreter: String = "https://www.youtube.com/s/desktop/abc/botguard.js",
    ): String {
        val config = """{"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20250901.00.00"}},"VISITOR_DATA":"$visitorData","EVENT_ID":"$eventId","WEB_PLAYER_CONTEXT_CONFIGS":{"WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH":{"serializedExperimentFlags":"$flags"}}}"""
        val challengeInner = """{"bgChallenge":{"program":"$program","globalName":"$globalName","interpreterUrl":{"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":"$interpreter"}}}"""
        // window.ytAtN({"R":"<challenge-json>"}) with the challenge JSON JS-escaped.
        val escaped = challengeInner.replace("\\", "\\\\").replace("\"", "\\\"")
        return "<html><head><script>ytcfg.set($config);</script></head>" +
            "<body><script>window.ytAtN({\"R\":\"$escaped\"});</script></body></html>"
    }

    @Test
    fun `parses content binding bootstrap`() {
        val bootstrap = parseYoutubePageAttestationBootstrap(homeHtml())
        assertEquals("CgtWMuc2hlbGxvIBD bundle", bootstrap.visitorData)
        assertEquals("WEB", bootstrap.clientName)
        assertEquals("2.20250901.00.00", bootstrap.clientVersion)
        assertEquals(YoutubePoTokenBinding.CONTENT, bootstrap.binding)
        assertEquals("EVENT123", bootstrap.eventId)
        assertEquals("PROG", bootstrap.challenge.program)
        assertEquals("GLOB", bootstrap.challenge.globalName)
        assertEquals(
            "https://www.youtube.com/s/desktop/abc/botguard.js",
            bootstrap.challenge.interpreterUrl
        )
        assertNull(bootstrap.challenge.interpreterJavascript)
    }

    @Test
    fun `parses session binding bootstrap`() {
        val bootstrap = parseYoutubePageAttestationBootstrap(
            homeHtml(flags = "html5_generate_session_po_token=true")
        )
        assertEquals(YoutubePoTokenBinding.SESSION, bootstrap.binding)
    }

    @Test
    fun `missing watch config defaults to content binding`() {
        val config = """{"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"1.0"}},"VISITOR_DATA":"V","EVENT_ID":"E"}"""
        val html = "<script>ytcfg.set($config);</script>" +
            "<script>window.ytAtN({\"R\":\"{\\\"bgChallenge\\\":{\\\"program\\\":\\\"P\\\",\\\"globalName\\\":\\\"G\\\",\\\"interpreterUrl\\\":{\\\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\\\":\\\"https://x/y.js\\\"}}}\"});</script>"
        assertEquals(
            YoutubePoTokenBinding.CONTENT,
            parseYoutubePageAttestationBootstrap(html).binding
        )
    }

    @Test
    fun `no supported binding flag yields NONE`() {
        val bootstrap = parseYoutubePageAttestationBootstrap(
            homeHtml(flags = "other_flag=false")
        )
        assertEquals(YoutubePoTokenBinding.NONE, bootstrap.binding)
    }

    @Test
    fun `missing challenge throws`() {
        assertFailsWith<Exception> {
            parseYoutubePageAttestationBootstrap("<html><body>no challenge here</body></html>")
        }
    }

    @Test
    fun `inline interpreter javascript is preferred when present`() {
        val challenge = parseSabrAttChallengeData(
            """{"bgChallenge":{"program":"P","globalName":"G",”interpreterJavascript”:{"privateDoNotAccessOrElseSafeScriptWrappedValue":"var x=1;"}}}"""
                .replace("”", "\"")
        )
        assertEquals("var x=1;", challenge.interpreterJavascript)
        assertNull(challenge.interpreterUrl)
    }

    @Test
    fun `protocol-relative interpreter url is absolutized`() {
        val challenge = parseSabrAttChallengeData(
            """{"bgChallenge":{"program":"P","globalName":"G","interpreterUrl":{"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":"//www.youtube.com/x.js"}}}"""
        )
        assertEquals("https://www.youtube.com/x.js", challenge.interpreterUrl)
    }
}
