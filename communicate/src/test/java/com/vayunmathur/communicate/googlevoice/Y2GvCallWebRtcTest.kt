package com.vayunmathur.communicate.googlevoice

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceWebSender
import com.vayunmathur.communicate.data.googlevoice.call.CallConnection
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Y2 wave-1 (Google Voice leg): [GoogleVoiceWebSender]'s token minter and
 * [GoogleVoiceCallManager]'s JVM-reachable state machine.
 *
 * The minter needs a real page load to mint a `"!…"` token; under Robolectric
 * the offscreen WebView never finishes loading, so the honest outcomes are:
 * no token (null / still pending), and its WebViewClient / WebChromeClient
 * callbacks driven directly against the installed clients. The manager is
 * driven through answer/reject/hangup with no SIP/WebRTC leg — every path
 * must fail closed to Ended and notify the Telecom bridge fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y2GvCallWebRtcTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeConnection : CallConnection {
        val active = AtomicInteger(0)
        val ended = AtomicInteger(0)
        override fun onCallActive() { active.incrementAndGet() }
        override fun onCallEnded() { ended.incrementAndGet() }
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "$what not met within ${timeoutMs}ms")
    }

    @BeforeTest
    fun setUp() {
        GoogleVoiceCallManager.connection = null
        GoogleVoiceCallManager.onIncomingCall = null
        GoogleVoiceCallManager.stopRegistration()
    }

    // ------------------------------------------------ GoogleVoiceCallManager

    @Test
    fun managerInitIdempotentAndMuteSpeakerDtmfWithoutLeg() {
        GoogleVoiceCallManager.init(ctx)
        GoogleVoiceCallManager.init(ctx) // second init must not reset the context
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
        GoogleVoiceCallManager.setMuted(true)
        GoogleVoiceCallManager.setSpeaker(true)
        assertTrue(GoogleVoiceCallManager.state.value.muted)
        assertTrue(GoogleVoiceCallManager.state.value.speaker)
        GoogleVoiceCallManager.sendDtmf("5") // no audio session: no-op
        GoogleVoiceCallManager.sendDtmf("123#")
        GoogleVoiceCallManager.setMuted(false)
        GoogleVoiceCallManager.setSpeaker(false)
        assertFalse(GoogleVoiceCallManager.state.value.muted)
        assertFalse(GoogleVoiceCallManager.state.value.speaker)
    }

    @Test
    fun managerRejectAndHangupEndAndNotifyConnection() {
        GoogleVoiceCallManager.init(ctx)
        val connection = FakeConnection()
        GoogleVoiceCallManager.connection = connection
        GoogleVoiceCallManager.reject()
        awaitUntil(what = "reject ends call") {
            GoogleVoiceCallManager.state.value.phase == CallPhase.Ended
        }
        assertEquals(1, connection.ended.get())
        assertEquals(0, connection.active.get())
        GoogleVoiceCallManager.clearEnded()
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
        // Clear-ended on Idle is a no-op (stays Idle, no extra bridge events).
        GoogleVoiceCallManager.clearEnded()
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)

        GoogleVoiceCallManager.hangup()
        awaitUntil(what = "hangup ends call") {
            GoogleVoiceCallManager.state.value.phase == CallPhase.Ended
        }
        assertEquals(2, connection.ended.get())
        assertEquals(0, connection.active.get())
        GoogleVoiceCallManager.clearEnded()
        GoogleVoiceCallManager.connection = null
    }

    @Test
    fun managerAnswerWithoutInviteEndsAndStopResets() {
        GoogleVoiceCallManager.init(ctx)
        val connection = FakeConnection()
        GoogleVoiceCallManager.connection = connection
        // No inbound INVITE (no pending SDP): building the media leg needs the
        // WebRTC native stack, so the answer fails. fail() notifies the bridge
        // and, because the line was never registered, immediately tears the
        // registration down — the observable end state is a fresh Idle, with
        // the Ended phase consumed internally.
        GoogleVoiceCallManager.answer()
        awaitUntil(what = "unanswerable call notifies bridge") { connection.ended.get() == 1 }
        assertEquals(0, connection.active.get())
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
        // stopRegistration tears everything down to a fresh Idle snapshot.
        GoogleVoiceCallManager.setMuted(true)
        GoogleVoiceCallManager.stopRegistration()
        val state = GoogleVoiceCallManager.state.value
        assertEquals(CallPhase.Idle, state.phase)
        assertEquals("", state.remoteNumber)
        assertFalse(state.muted)
        assertFalse(state.speaker)
        GoogleVoiceCallManager.connection = null
    }

    @Test
    fun managerIncomingCallbackSlotIsAssignableAndClearable() {
        GoogleVoiceCallManager.init(ctx)
        var seen: String? = null
        GoogleVoiceCallManager.onIncomingCall = { from -> seen = from }
        assertNotNull(GoogleVoiceCallManager.onIncomingCall)
        GoogleVoiceCallManager.onIncomingCall?.invoke("+15551234567")
        assertEquals("+15551234567", seen)
        GoogleVoiceCallManager.onIncomingCall = null
        assertNull(GoogleVoiceCallManager.onIncomingCall)
    }

    // ------------------------------------------------------- bot-token parse

    @Test
    fun botTokenExtractionFindsNestedToken() {
        assertEquals("!tok123", GoogleVoiceParser.extractBotToken("""["x",null,"!tok123"]"""))
        assertEquals("!nested", GoogleVoiceParser.extractBotToken("""[["a",1],["!nested"]]"""))
        assertNull(GoogleVoiceParser.extractBotToken("""["x",null]"""))
        assertNull(GoogleVoiceParser.extractBotToken("""{"k":"!notarray"}"""))
        assertNull(GoogleVoiceParser.extractBotToken("not json at all"))
        assertNull(GoogleVoiceParser.extractBotToken(""))
    }

    // -------------------------------------------------- GoogleVoiceWebSender

    /**
     * Pump the (paused) Robolectric main looper until the minter installs its
     * offscreen WebView into the activity decor. The mint runs on a standalone
     * scope: as a `runBlocking` child it could never finish, because its
     * Main-thread WebView setup needs the very looper the test would block.
     */
    private fun awaitWebView(activity: Activity): WebView {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val decor = activity.window.decorView as ViewGroup
            val webView = (0 until decor.childCount)
                .map { decor.getChildAt(it) }
                .filterIsInstance<WebView>()
                .firstOrNull()
            if (webView != null) return webView
            Thread.sleep(25)
        }
        throw AssertionError("offscreen WebView was not installed within 10s")
    }

    private fun cancelAndDrain(job: kotlinx.coroutines.Job) {
        job.cancel()
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(job.isCompleted, "cancelled mint did not unwind within 10s")
    }

    @Test
    fun webSenderInstallsWebViewAndCallbacksWithoutMinting() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val minted = AtomicBoolean(false)
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            GoogleVoiceWebSender.mintToken(activity, "+15551234567", "hello")
            minted.set(true)
        }
        val webView = awaitWebView(activity)
        // The page never loads under Robolectric, so no token can be minted.
        assertFalse(minted.get(), "mint must still be waiting for the page")
        assertTrue(job.isActive)
        // The installed clients are drivable directly.
        val client = webView.webViewClient
        assertNotNull(client, "WebSender must install a WebViewClient")
        client.onPageStarted(webView, "https://voice.google.com/u/0/messages", null)
        client.onPageFinished(webView, "https://voice.google.com/u/0/messages")
        // The injected-guard makes a second finish a no-op (must not throw).
        client.onPageFinished(webView, "https://voice.google.com/u/0/messages")
        client.onPageFinished(webView, "https://example.invalid/other")
        val chrome = webView.webChromeClient
        assertNotNull(chrome, "WebSender must install a WebChromeClient")
        var chooserValue: Array<Uri>? = null
        val handled = chrome.onShowFileChooser(
            webView,
            object : ValueCallback<Array<Uri>> {
                override fun onReceiveValue(value: Array<Uri>?) { chooserValue = value }
            },
            null,
        )
        assertTrue(handled, "file chooser must be answered by the sender")
        assertNotNull(chooserValue)
        assertEquals(0, chooserValue!!.size, "no attachments on this mint")
        // Still no token: only a captured sendsms body could complete the mint.
        assertFalse(minted.get())
        cancelAndDrain(job)
    }

    @Test
    fun webSenderPreparedBodyWithAttachmentAlsoWaitsForPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val attachment = CommunicateAttachment(
            contentUri = "content://media/1", mimeType = "image/jpeg", fileName = "a.jpg",
        )
        val minted = AtomicBoolean(false)
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            GoogleVoiceWebSender.mintPreparedBody(activity, "+15551234567", "hi", listOf(attachment))
            minted.set(true)
        }
        val webView = awaitWebView(activity)
        assertFalse(minted.get(), "attachment mint must also wait for the page")
        assertTrue(job.isActive)
        // The file chooser now hands back the pending attachment URI.
        var chooserValue: Array<Uri>? = null
        val handled = webView.webChromeClient!!.onShowFileChooser(
            webView,
            object : ValueCallback<Array<Uri>> {
                override fun onReceiveValue(value: Array<Uri>?) { chooserValue = value }
            },
            null,
        )
        assertTrue(handled)
        assertEquals(listOf(Uri.parse("content://media/1")), chooserValue!!.toList())
        cancelAndDrain(job)
    }
}
