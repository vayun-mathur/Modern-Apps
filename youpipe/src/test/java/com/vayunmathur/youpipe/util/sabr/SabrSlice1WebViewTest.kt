package com.vayunmathur.youpipe.util.sabr

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) second pass: home-page attestation bootstrap parsing,
 * the shared WebView runtime's init-failure machinery under Robolectric
 * (its shadow WebView never runs the page's JS, so every attempt times
 * out and fails cleanly), and the token provider/generator failure paths
 * driven by a fake downloader serving a crafted home page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SabrSlice1WebViewTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    companion object {
        fun homeHtml(
            bindingFlags: String? = null,
            clientName: String = "WEB",
            visitor: String? = "vis%3D",
            interpreterUrl: String? = null,
        ): String {
            val challengeJson = if (interpreterUrl != null) {
                """{"bgChallenge":{"program":"PROG","globalName":"GLOB","interpreterUrl":{"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":"$interpreterUrl"}}}"""
            } else {
                """{"bgChallenge":{"program":"PROG","globalName":"GLOB","interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"JS_CODE"}}}"""
            }
            val watchConfig = if (bindingFlags == null) "" else
                ""","WEB_PLAYER_CONTEXT_CONFIGS":{"WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH":{"serializedExperimentFlags":"$bindingFlags"}}"""
            val visitorField = if (visitor == null) "" else ""","EOM_VISITOR_DATA":"$visitor""""
            return """
                <html><script>
                ytcfg.set({"EVENT_ID":"evt-1"$visitorField,"INNERTUBE_CONTEXT":{"client":{"clientName":"$clientName","clientVersion":"2.5.1"}}$watchConfig});
                window.ytAtN({'R': '$challengeJson'});
                </script></html>
            """.trimIndent()
        }
    }

    // ---- bootstrap parsing ----

    @Test fun bootstrapParsesContentBindingByDefault() {
        val parsed = parseYoutubePageAttestationBootstrap(homeHtml())
        assertEquals("vis=", parsed.visitorData); assertEquals("WEB", parsed.clientName); assertEquals("2.5.1", parsed.clientVersion)
        assertEquals(YoutubePoTokenBinding.CONTENT, parsed.binding); assertEquals("evt-1", parsed.eventId)
        assertEquals("PROG", parsed.challenge.program); assertEquals("GLOB", parsed.challenge.globalName)
        assertEquals("JS_CODE", parsed.challenge.interpreterJavascript); assertEquals(null, parsed.challenge.interpreterUrl)
    }

    @Test fun bootstrapBindingsFromExperimentFlags() {
        assertEquals(YoutubePoTokenBinding.SESSION, parseYoutubePageAttestationBootstrap(homeHtml("html5_generate_session_po_token=true")).binding)
        assertEquals(YoutubePoTokenBinding.CONTENT, parseYoutubePageAttestationBootstrap(homeHtml("html5_generate_content_po_token=true&other=1")).binding)
        assertEquals(YoutubePoTokenBinding.NONE, parseYoutubePageAttestationBootstrap(homeHtml("unrelated=true")).binding)
    }

    @Test fun bootstrapInterpreterUrlGetsHttpsPrefix() {
        val parsed = parseYoutubePageAttestationBootstrap(homeHtml(interpreterUrl = "//example.com/interp.js"))
        assertEquals("https://example.com/interp.js", parsed.challenge.interpreterUrl); assertEquals(null, parsed.challenge.interpreterJavascript)
        val absolute = parseYoutubePageAttestationBootstrap(homeHtml(interpreterUrl = "https://example.com/i.js"))
        assertEquals("https://example.com/i.js", absolute.challenge.interpreterUrl)
    }

    @Test fun bootstrapRejectsMissingPieces() {
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap("<html>no calls</html>") }
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap(homeHtml(clientName = "ANDROID")) }
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap(homeHtml(visitor = null)) }
        // Challenge JSON without a program is malformed and skipped, leaving no challenge.
        val noProgram = homeHtml().replace("\"program\":\"PROG\",", "")
        assertFailsWith<SabrProtocolException> { parseYoutubePageAttestationBootstrap(noProgram) }
        assertFailsWith<SabrProtocolException> { parseSabrAttChallengeData("{}") }
        assertFailsWith<SabrProtocolException> { parseSabrAttChallengeData("""{"bgChallenge":{"program":"P","globalName":"G"}}""") }
    }

    // ---- WebView runtime failure machinery ----

    @Test fun webViewRuntimeLoadsAssetsAndFailsInitCleanly() {
        val runtime = SharedWebViewRuntime.get(ctx)
        assertEquals(runtime, SharedWebViewRuntime.get(ctx))
        assertTrue(runtime.loadAsset("sabr_po_token.js").isNotBlank())
        assertFailsWith<IllegalStateException> { runtime.loadAsset("no-such-asset.js") }
        val cb = object : SharedWebViewRuntime.SabrLocalDomCallbacks {
            override fun onJsInitializationError(error: String) {}
            override fun onRunBotguardResult(botguardResponse: String) {}
            override fun onMinterReady() {}
            override fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {}
            override fun onObtainPoTokenError(identifier: String, error: String) {}
        }
        val id = runtime.registerSabrLocalDomCallbacks(cb); assertTrue(id.isNotBlank())
        runtime.unregisterSabrLocalDomCallbacks(id); runtime.unregisterSabrLocalDomCallbacks("never-registered")

        // Warm up: Robolectric's WebView never executes the runtime document's bridge call,
        // so both ready-callback attempts time out (5s each, driven by the looper clock).
        var failure: Throwable? = null
        runtime.warmUp { failure = it }
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)
        // ensureReady on a background thread must surface the stored init failure.
        val error = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
        Thread {
            error.set(runCatching { runtime.ensureReady(1000, "test op") }.exceptionOrNull()); done.countDown()
        }.start()
        // Pump the main looper while the background thread waits.
        val deadline = System.currentTimeMillis() + 15_000
        while (!done.await(50, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadline) shadowOf(Looper.getMainLooper()).idle()
        assertTrue(error.get() is IllegalStateException)
        // Async JS evaluation reports through the error callback instead of throwing.
        var asyncError: Throwable? = null
        val posted = runtime.evaluateJavascript("1+1", null) { asyncError = it }
        assertEquals(false, posted); assertTrue(asyncError is IllegalStateException)
        assertFailsWith<IllegalStateException> { runtime.evaluateJavascriptBlocking("1", 100, "main-thread op") } // cannot wait on main
    }

    @Test fun webViewRuntimeReplaysStoredFailureOnWarmUp() {
        val runtime = SharedWebViewRuntime.get(ctx)
        runtime.warmUp()
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)
        // Second warm-up with the failure already stored: the callback fires immediately.
        var replayed: Throwable? = null
        runtime.warmUp { replayed = it }
        assertTrue(replayed is IllegalStateException, "expected stored init failure, got $replayed")
        runtime.warmUp() // no callback: still no crash, no new attempt while latch exists
        // Blocking evaluation on a background thread surfaces the same stored failure.
        val error = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
        Thread {
            error.set(runCatching { runtime.evaluateJavascriptBlocking("1+1", 2_000, "replay op") }.exceptionOrNull()); done.countDown()
        }.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (!done.await(50, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadline) shadowOf(Looper.getMainLooper()).idle()
        assertTrue(error.get() is IllegalStateException)
    }

    // ---- generator + provider against a fake home page ----

    private class FakeDownloader(private val body: String, private val code: Int = 200) : Downloader() {
        override fun execute(request: Request): Response =
            Response(code, null, null, body, request.url)
    }

    @Test fun providerFailsCleanlyWhenWebViewCannotMint() {
        NewPipe.init(FakeDownloader(homeHtml()))
        val provider = LocalDomPoTokenProvider(ctx)
        provider.warmUp()
        // buildMintState runs on the provider executor: home fetch + bootstrap parse succeed,
        // then generator creation fails because the WebView runtime cannot initialize here.
        val err = assertFailsWith<SabrProtocolException> { provider.getPoTokenBytes("vidS1") }
        assertTrue(err.message!!.contains("initialization"))
        provider.invalidate() // no completed state: safe no-op
        assertFailsWith<SabrProtocolException> { provider.getSessionPoToken("WEB", org.schabi.newpipe.extractor.localization.Localization("en", "US"), org.schabi.newpipe.extractor.localization.ContentCountry("US"), false) }
    }

    @Test fun generatorCreateFailsWithoutWebViewRuntime() {
        NewPipe.init(FakeDownloader(homeHtml()))
        val bootstrap = parseYoutubePageAttestationBootstrap(homeHtml())
        val err = assertFailsWith<SabrProtocolException> { LocalDomPoTokenGenerator.create(ctx, bootstrap) }
        assertTrue(err.message!!.isNotBlank())
    }
}
