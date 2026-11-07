package com.vayunmathur.youpipe.util.sabr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) device pass: the shared WebView runtime on a real WebView
 * (the runtime document's JS actually executes and calls the bridge back),
 * and generator creation against a fake attestation bootstrap.
 */
@RunWith(AndroidJUnit4::class)
class SabrSlice1DeviceRuntimeTest {

    @Test fun webViewRuntimeBecomesReadyAndEvaluates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = SharedWebViewRuntime.get(context)
        runtime.warmUp()
        runtime.ensureReady(20_000, "device runtime test")
        // Real JS evaluation on the device WebView.
        assertEquals("2", runtime.evaluateJavascriptBlocking("1+1", 10_000, "device eval"))
        assertTrue(runtime.evaluateJavascriptBlocking("'a'+'b'", 10_000, "device eval").contains("ab"))
        // Async evaluation with a callback.
        val latch = CountDownLatch(1); val value = AtomicReference<String?>()
        val posted = runtime.evaluateJavascript("40+2", { value.set(it); latch.countDown() }, { latch.countDown() })
        assertTrue(posted)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(value.get() == null || value.get()!!.contains("42"))
        assertTrue(runtime.loadAsset("sabr_po_token.js").isNotBlank())
    }

    @Test fun generatorCreateAgainstFakeBootstrap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SharedWebViewRuntime.get(context).ensureReady(20_000, "device generator test")
        val bootstrap = YoutubePageAttestationBootstrap(
            visitorData = "visitor",
            clientName = "WEB",
            clientVersion = "2.5.1",
            binding = YoutubePoTokenBinding.CONTENT,
            eventId = "evt-1",
            challenge = SabrAttChallengeData("PROG", "GLOB", "JS_CODE", null),
        )
        // The fake BotGuard program cannot mint: creation must fail with a protocol
        // exception (initialization error from the JS side), never hang or crash.
        var created: LocalDomPoTokenGenerator? = null
        val failure = runCatching { created = LocalDomPoTokenGenerator.create(context, bootstrap) }.exceptionOrNull()
        if (created != null) {
            assertTrue(created!!.isExpired()) // no successful BotGuard run -> no expiration set
            created!!.close(); created!!.close()
            val mintFailure = runCatching { created!!.mint("vid") }.exceptionOrNull()
            assertTrue("closed generator must refuse to mint", mintFailure is SabrProtocolException)
        } else {
            assertTrue("expected SabrProtocolException, got $failure", failure is SabrProtocolException)
        }
    }
}
