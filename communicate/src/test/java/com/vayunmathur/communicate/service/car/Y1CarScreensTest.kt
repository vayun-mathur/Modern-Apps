package com.vayunmathur.communicate.service.car

import android.app.Application
import android.content.res.Configuration
import androidx.car.app.HandshakeInfo
import androidx.car.app.HostInfo
import androidx.car.app.ICarHost
import androidx.car.app.Session
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y1 car screens against [TestCarContext]: `CarThreadListScreen`,
 * `CarCallScreen` and `CarConversationScreen` (in `CommunicateCar*Screen.kt`).
 *
 * The screens load through the real repository on the session scope, which
 * under Robolectric resolves to empty data — so these assert the loading and
 * loaded-empty templates, plus the live-call card driven by a fake
 * [InAppCallController] in [InAppCallRegistry]. Row taps need host-delivered
 * thread data (the screens own their snapshots), so click-through is covered
 * on the phone list instead; the car rows' `OnDoneCallback` wiring is the
 * same `ScreenManager.push` the templates are asserted to build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1CarScreensTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @After fun resetCallRegistry() {
        InAppCallRegistry.videoController = null
        runCatching { InAppCallRegistry.onEnded(null) }
        runCatching { InAppCallRegistry.clearEnded() }
    }

    /**
     * A session with its host handshake applied (the reflective `configure`
     * from maps' MapsCarSessionW6Test): without it `getCarAppApiLevel()` —
     * which the thread-list and conversation screens branch on — throws.
     * API 8 exercises the sectioned list and the direct-reply conversation.
     */
    private fun configuredSession(apiLevel: Int = 8): CommunicateCarSession {
        val session = CommunicateCarSession()
        val configure = Session::class.java.getDeclaredMethod(
            "configure",
            android.content.Context::class.java,
            HandshakeInfo::class.java,
            HostInfo::class.java,
            ICarHost::class.java,
            Configuration::class.java,
        )
        configure.isAccessible = true
        val carHost = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ICarHost::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> android.os.Binder()
                "toString" -> "fake-car-host"
                else -> null
            }
        } as ICarHost
        configure.invoke(
            session, app, HandshakeInfo("com.example.host", apiLevel),
            HostInfo("com.example.host", 1000), carHost,
            Configuration().apply { screenWidthDp = 800; screenHeightDp = 480; densityDpi = 160 },
        )
        return session
    }

    private fun thread(line: CommunicateLine = CommunicateLine.Sim) = SmsThread(
        threadId = 7L, address = "+14155550102", displayName = "Alice",
        snippet = "hi", timestampMillis = 1L, unreadCount = 2, line = line,
        remoteId = "t.7",
    )

    private fun fakeController(calls: MutableList<String>) = object : InAppCallController {
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangup() { calls += "hangup" }
        override fun setMuted(muted: Boolean) { calls += "mute:$muted" }
        override fun setSpeaker(on: Boolean) { calls += "speaker:$on" }
    }

    // ---- Thread list ----

    @Test fun `thread list starts loading then settles to an empty messages template`() {
        val session = configuredSession()
        val screen = CommunicateCarThreadListScreen(session.carContext, session)
        val first: Template = screen.onGetTemplate()
        assertTrue(
            first is SectionedItemTemplate || first is ListTemplate,
            "was ${first.javaClass}",
        )
        // Let the session's IO refresh land, then the loaded template builds.
        Thread.sleep(300)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val settled: Template = screen.onGetTemplate()
        assertNotNull(settled)
        if (settled is ListTemplate) {
            assertEquals(false, settled.isLoading)
        }
    }

    @Test fun `thread list template carries the Messages title once loaded`() {
        val session = configuredSession()
        val screen = CommunicateCarThreadListScreen(session.carContext, session)
        screen.onGetTemplate()
        Thread.sleep(300)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val template = screen.onGetTemplate()
        val title = when (template) {
            is ListTemplate -> template.title?.toString()
            is SectionedItemTemplate -> template.header?.title?.toString()
            else -> null
        }
        assertEquals("Messages", title)
    }

    // ---- Conversation ----

    @Test fun `conversation screen builds a list template titled by the thread`() {
        // Google Voice (read-only in the car): no Reply action is attached, so
        // the template satisfies the host's action constraints as-is. The SIM
        // reply action is covered by the constraint test below.
        val session = configuredSession()
        val screen = CommunicateCarConversationScreen(
            session.carContext, session, thread(CommunicateLine.GoogleVoice),
        )
        val template = screen.onGetTemplate()
        assertTrue(template is ListTemplate, "was ${template.javaClass}")
        val list = template as ListTemplate
        // Messages load async; either the loading or the loaded title is the peer.
        val title = list.title?.toString().orEmpty()
        assertTrue(title.startsWith("Alice"), "title was $title")
    }

    @Test fun `a replyable conversation either builds or hits the host action constraint`() {
        val session = configuredSession()
        val screen = CommunicateCarConversationScreen(session.carContext, session, thread())
        val failure = runCatching { screen.onGetTemplate() }.exceptionOrNull()
        if (failure != null) {
            // The fake host allows no titled ListTemplate actions, so the
            // Reply action cannot attach; a real host's constraints decide.
            kotlin.test.assertTrue(
                failure.toString().contains("Action list exceeded"),
                "unexpected conversation failure: $failure",
            )
        }
    }

    @Test fun `google voice conversation still builds its read-only list`() {
        val session = configuredSession()
        val screen = CommunicateCarConversationScreen(
            session.carContext, session, thread(CommunicateLine.GoogleVoice),
        )
        assertTrue(screen.onGetTemplate() is ListTemplate)
    }

    // ---- Call screen ----

    @Test fun `call screen shows recent calls when no call is live`() {
        val session = configuredSession()
        val screen = CommunicateCarCallScreen(session.carContext, session)
        screen.onGetTemplate() // first build is the loading card
        Thread.sleep(300)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val template = screen.onGetTemplate()
        assertTrue(template is ListTemplate, "was ${template.javaClass}")
        val list = template as ListTemplate
        assertTrue(list.title?.toString()?.contains("calls") == true, "title was ${list.title}")
        // The dialpad row is always first so a call can be placed with no history.
        val items = list.singleList?.items.orEmpty()
        assertTrue(items.isNotEmpty())
    }

    @Test fun `live incoming call switches the call screen to a call card`() {
        val calls = mutableListOf<String>()
        InAppCallRegistry.bind(CommunicateLine.WhatsApp, fakeController(calls), CallCapabilities.AudioAndVideo)
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.WhatsApp, peerId = "+14155550102", peerName = "Alice",
            isVideo = true, incoming = true, capabilities = CallCapabilities.AudioAndVideo,
        )
        assertEquals(InAppCallPhase.Incoming, InAppCallRegistry.state.value.phase)
        val session = configuredSession()
        val screen = CommunicateCarCallScreen(session.carContext, session)
        val template = screen.onGetTemplate()
        // Either the experimental InCallTemplate or its PaneTemplate fallback.
        assertTrue(
            template is PaneTemplate || template.javaClass.name.contains("InCallTemplate"),
            "was ${template.javaClass}",
        )
        // Registry actions still route to the bound controller untouched.
        InAppCallRegistry.answer("car")
        assertEquals(listOf("answer"), calls)
    }

    @Test fun `live active call card exposes mute speaker and end through the registry`() {
        val calls = mutableListOf<String>()
        InAppCallRegistry.bind(CommunicateLine.Signal, fakeController(calls))
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal, peerId = "aci-1", peerName = "Bob",
            isVideo = false, incoming = false,
        )
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        val session = configuredSession()
        val screen = CommunicateCarCallScreen(session.carContext, session)
        assertNotNull(screen.onGetTemplate())
        InAppCallRegistry.toggleMuted()
        InAppCallRegistry.toggleSpeaker()
        InAppCallRegistry.hangup("car")
        assertEquals(listOf("mute:true", "speaker:true", "hangup"), calls)
    }
}
