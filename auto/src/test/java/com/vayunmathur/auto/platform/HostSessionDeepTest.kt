package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.IAppHost
import androidx.car.app.IAppManager
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.serialization.Bundleable
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.carhost.HostTemplate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep [HostSession] coverage: host binders, touch slop routing, handshake
 * outcomes and template fetching against fake car-app binders. The fakes
 * answer binder callbacks synchronously, exactly as a well-behaved car app
 * would, so the session's real round-trip code runs end to end.
 */
@RunWith(RobolectricTestRunner::class)
class HostSessionDeepTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val component = ComponentName("com.example.maps", "com.example.maps.Svc")
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    // -- reflection helpers (private session internals under test) -----------
    private fun Any.field(name: String): Any? {
        val f = HostSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(this)
    }

    private fun Any.setField(name: String, value: Any?) {
        val f = HostSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        f.set(this, value)
    }

    private fun HostSession.runHandshake(app: ICarApp) {
        val m = HostSession::class.java.getDeclaredMethod("runHandshake", ICarApp::class.java)
            .apply { isAccessible = true }
        m.invoke(this, app)
    }

    // -- fakes ---------------------------------------------------------------
    private class FakeSurfaceCallback : ISurfaceCallback.Stub() {
        val scrolls = mutableListOf<Pair<Float, Float>>()
        val clicks = mutableListOf<Pair<Float, Float>>()
        var destroyed = 0
        var available = 0
        var fail = false

        override fun onSurfaceAvailable(b: Bundleable?, cb: IOnDoneCallback?) {
            available++; cb?.onSuccess(null)
        }

        override fun onVisibleAreaChanged(r: android.graphics.Rect?, cb: IOnDoneCallback?) = Unit
        override fun onStableAreaChanged(r: android.graphics.Rect?, cb: IOnDoneCallback?) = Unit
        override fun onSurfaceDestroyed(b: Bundleable?, cb: IOnDoneCallback?) {
            destroyed++; cb?.onSuccess(null)
        }

        override fun onScroll(dx: Float, dy: Float) {
            if (fail) throw IllegalStateException("scroll failed")
            scrolls += dx to dy
        }

        override fun onFling(vx: Float, vy: Float) = Unit
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = Unit
        override fun onClick(x: Float, y: Float) {
            if (fail) throw IllegalStateException("click failed")
            clicks += x to y
        }

        override fun getInterfaceVersion(): Int = ISurfaceCallback.VERSION
    }

    private class FakeManager(
        private val template: TemplateWrapper?,
        private val failTemplate: Boolean = false,
    ) : IAppManager.Stub() {
        override fun getTemplate(cb: IOnDoneCallback?) {
            if (failTemplate) cb?.onFailure(null)
            else cb?.onSuccess(template?.let { Bundleable.create(it) })
        }

        override fun onBackPressed(cb: IOnDoneCallback?) = Unit
        override fun startLocationUpdates(cb: IOnDoneCallback?) = Unit
        override fun stopLocationUpdates(cb: IOnDoneCallback?) = Unit
        override fun getInterfaceVersion(): Int = IAppManager.VERSION
    }

    private class FakeCarApp(
        var handshake: Mode = Mode.OK,
        var create: Mode = Mode.OK,
        var manager: IAppManager? = null,
        var managerMode: Mode = Mode.OK,
        var throwOnGetManager: Boolean = false,
    ) : ICarApp.Stub() {
        enum class Mode { OK, FAIL, THROW }

        val configLatch = CountDownLatch(1)
        val lastConfig = AtomicReference<Configuration?>()
        var pauseCalls = 0
        var stopCalls = 0

        private fun answer(mode: Mode, cb: IOnDoneCallback?) {
            when (mode) {
                Mode.OK -> cb?.onSuccess(null)
                Mode.FAIL -> cb?.onFailure(null)
                Mode.THROW -> throw IllegalStateException("binder dead")
            }
        }

        override fun onHandshakeCompleted(info: Bundleable?, cb: IOnDoneCallback?) =
            answer(handshake, cb)

        override fun onAppCreate(
            host: ICarHost?, intent: Intent?, config: Configuration?, cb: IOnDoneCallback?,
        ) = answer(create, cb)

        override fun onAppStart(cb: IOnDoneCallback?) = answer(create, cb)
        override fun onAppResume(cb: IOnDoneCallback?) = answer(create, cb)
        override fun onAppPause(cb: IOnDoneCallback?) { pauseCalls++; cb?.onSuccess(null) }
        override fun onAppStop(cb: IOnDoneCallback?) { stopCalls++; cb?.onSuccess(null) }
        override fun onNewIntent(intent: Intent?, cb: IOnDoneCallback?) = Unit

        override fun onConfigurationChanged(config: Configuration?, cb: IOnDoneCallback?) {
            lastConfig.set(config); configLatch.countDown(); cb?.onSuccess(null)
        }

        override fun getManager(type: String?, cb: IOnDoneCallback?) {
            if (throwOnGetManager) throw IllegalStateException("getManager dead")
            when (managerMode) {
                Mode.OK -> cb?.onSuccess(manager?.let { Bundleable.create(it) })
                Mode.FAIL -> cb?.onFailure(null)
                Mode.THROW -> throw IllegalStateException("getManager dead")
            }
        }

        override fun getAppInfo(cb: IOnDoneCallback?) = Unit
        override fun getAppTheme(cb: IOnDoneCallback?) = Unit
        override fun getInterfaceVersion(): Int = ICarApp.VERSION
    }

    private fun session(
        templates: MutableList<HostTemplate> = mutableListOf(),
        refused: (() -> Unit)? = null,
    ): HostSession {
        val onRefused: () -> Unit = refused ?: {}
        return HostSession(ctx, component, "nav", "Maps", onRefused = onRefused) { templates += it }
    }

    private fun paneWrapper(title: String): TemplateWrapper =
        TemplateWrapper.wrap(
            PaneTemplate.Builder(Pane.Builder().setLoading(true).build()).setTitle(title).build(),
        )

    // -- touch routing ---------------------------------------------------------
    @Test fun `map touch slop suppresses small moves and click fires on tap`() {
        val s = session()
        val cb = FakeSurfaceCallback()
        s.setField("surfaceCallback", cb)
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_DOWN, 100f, 100f))
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_MOVE, 102f, 101f)) // inside slop: no scroll
        assertTrue(cb.scrolls.isEmpty())
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_MOVE, 160f, 100f)) // drag: scrolls
        assertEquals(1, cb.scrolls.size)
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_UP, 160f, 100f)) // was a drag: no click
        assertTrue(cb.clicks.isEmpty())
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_DOWN, 40f, 50f))
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_UP, 40f, 50f)) // clean tap: click
        assertEquals(listOf(40f to 50f), cb.clicks)
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_CANCEL, 0f, 0f))
        assertFalse(s.injectMapTouch(999, 0f, 0f)) // unknown verb drops
    }

    @Test fun `map touch with failing callback reports not consumed`() {
        val s = session()
        val cb = FakeSurfaceCallback().apply { fail = true }
        s.setField("surfaceCallback", cb)
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_DOWN, 10f, 10f))
        assertFalse(s.injectMapTouch(MotionEvent.ACTION_MOVE, 90f, 10f))
        // The drag already claimed the gesture, so UP attempts no click and is consumed.
        assertTrue(s.injectMapTouch(MotionEvent.ACTION_UP, 90f, 10f))
        assertTrue(cb.clicks.isEmpty())
    }

    @Test fun `clear surface notifies the app and drops the local surface`() {
        val s = session()
        val cb = FakeSurfaceCallback()
        s.setField("surfaceCallback", cb)
        val surface = Surface(SurfaceTexture(3))
        s.setField("localSurface", surface)
        s.setField("localWidth", 800)
        s.setField("localHeight", 480)
        s.clearSurface()
        assertEquals(1, cb.destroyed)
        assertNull(s.field("localSurface"))
        s.clearSurface() // already clear: no second destroy
        assertEquals(1, cb.destroyed)
        surface.release()
    }

    // -- handshake -------------------------------------------------------------
    @Test fun `successful handshake loads then posts the parsed template`() {
        val templates = mutableListOf<HostTemplate>()
        val s = session(templates)
        val app = FakeCarApp(manager = FakeManager(paneWrapper("Home")))
        s.runHandshake(app)
        idleMain()
        assertTrue(templates.any { it is HostTemplate.Pane && it.loading })
        assertTrue(templates.filterIsInstance<HostTemplate.Pane>().any { it.title == "Home" })
        // Night now forwards: handshake completed, so the config change is sent.
        s.setNight(true)
        assertTrue(app.configLatch.await(5, TimeUnit.SECONDS))
        val night = app.lastConfig.get()!!.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertEquals(Configuration.UI_MODE_NIGHT_YES, night)
        s.setNight(false)
        // Unbind pauses/stops the hosted app.
        s.unbind()
        assertEquals(1, app.pauseCalls)
        assertEquals(1, app.stopCalls)
    }

    @Test fun `refused handshake notifies and posts the approval message`() {
        val templates = mutableListOf<HostTemplate>()
        var refusedCalls = 0
        val s = session(templates, refused = { refusedCalls++ })
        s.runHandshake(FakeCarApp(handshake = FakeCarApp.Mode.FAIL))
        idleMain()
        assertEquals(1, refusedCalls)
        val msg = templates.filterIsInstance<HostTemplate.Message>().single()
        assertTrue(msg.message!!.contains("only works with car systems"))
    }

    @Test fun `handshake binder throw and create failure both post did-not-respond`() {
        val t1 = mutableListOf<HostTemplate>()
        session(t1).runHandshake(FakeCarApp(handshake = FakeCarApp.Mode.THROW))
        idleMain()
        assertTrue(t1.filterIsInstance<HostTemplate.Message>().any { it.message!!.contains("didn't respond") })

        val t2 = mutableListOf<HostTemplate>()
        session(t2).runHandshake(FakeCarApp(create = FakeCarApp.Mode.FAIL))
        idleMain()
        assertTrue(t2.filterIsInstance<HostTemplate.Message>().any { it.message!!.contains("couldn't start") })
    }

    @Test fun `fetch template failure modes all post did-not-respond`() {
        val modes = listOf(
            FakeCarApp(managerMode = FakeCarApp.Mode.FAIL),
            FakeCarApp(manager = null), // success with no manager bundle
            FakeCarApp(throwOnGetManager = true),
            FakeCarApp(manager = FakeManager(null)), // manager but no template
            FakeCarApp(manager = FakeManager(null, failTemplate = true)),
        )
        for (app in modes) {
            val templates = mutableListOf<HostTemplate>()
            val s = session(templates)
            s.setField("carApp", app)
            s.fetchTemplate()
            idleMain()
            assertTrue(
                templates.filterIsInstance<HostTemplate.Message>()
                    .any { it.message!!.contains("didn't respond") },
                "expected did-not-respond for $app, got $templates",
            )
        }
    }

    @Test fun `night before handshake and surface push without callback are no-ops`() {
        val s = session()
        s.setNight(true) // no app bound
        val app = FakeCarApp()
        s.setField("carApp", app)
        s.setNight(true) // app present but handshake not done: still no send
        assertEquals(1L, app.configLatch.count)
        val surface = Surface(SurfaceTexture(4))
        s.setSurface(surface, 800, 480) // no surface callback yet: stores + push no-ops
        Thread.sleep(150) // pushSurface runs on its own thread; must simply not crash
        s.clearSurface()
        surface.release()
    }

    // -- host binders ------------------------------------------------------------
    @Test fun `car host binder routes every host type and rejects unknown ones`() {
        val s = session()
        val carHost = s.field("carHostBinder") as ICarHost
        assertNotNull(carHost.getHost(CarContext.APP_SERVICE))
        assertNotNull(carHost.getHost(CarContext.NAVIGATION_SERVICE))
        assertNotNull(carHost.getHost(CarContext.CONSTRAINT_SERVICE))
        assertNotNull(carHost.getHost(CarContext.MEDIA_PLAYBACK_SERVICE))
        assertNotNull(carHost.getHost(CarContext.SUGGESTION_SERVICE))
        assertFailsWith<java.security.InvalidParameterException> { carHost.getHost("bogus") }
        assertFailsWith<java.security.InvalidParameterException> { carHost.getHost(null) }
        assertEquals(ICarHost.VERSION, carHost.interfaceVersion)
        carHost.finish()
        carHost.startCarApp(null) // null intent ignored
        // Unresolvable activity: start fails inside runCatching, never crashes.
        carHost.startCarApp(Intent(Intent.ACTION_MAIN).setClassName("com.nope", "com.nope.Main"))
    }

    @Test fun `app host binder records surface callback and rejects microphone`() {
        val s = session()
        val appHost = s.field("appHostBinder") as IAppHost
        val cb = FakeSurfaceCallback()
        appHost.setSurfaceCallback(cb)
        assertEquals(cb, s.field("surfaceCallback"))
        appHost.showToast("hello", 0)
        appHost.showAlert(null)
        appHost.dismissAlert(1)
        appHost.sendLocation(null)
        appHost.invalidate() // no car app: fetch returns immediately
        assertFailsWith<android.os.RemoteException> { appHost.openMicrophone(null) }
        assertEquals(IAppHost.VERSION, appHost.interfaceVersion)
        appHost.setSurfaceCallback(null)
        assertNull(s.field("surfaceCallback"))
    }

    @Test fun `nav constraint media and suggestion host binders behave`() {
        val templates = mutableListOf<HostTemplate>()
        val s = session(templates)
        val nav = s.field("navHostBinder") as INavigationHost
        nav.navigationStarted() // resets the task-stack mirror
        nav.updateTrip(null)
        nav.setVoiceAssistantCapabilities(null)
        @Suppress("UNCHECKED_CAST")
        val voice = HostSession::class.java.getDeclaredField("voiceCapabilities")
            .apply { isAccessible = true }.get(s) as java.util.concurrent.atomic.AtomicReference<Boolean>
        assertFalse(voice.get())
        nav.setVoiceAssistantCapabilities(Bundleable.create("caps"))
        assertTrue(voice.get())
        nav.navigationEnded()
        idleMain()
        assertTrue(templates.any { it is HostTemplate.Pane && it.title == null })
        assertEquals(INavigationHost.VERSION, nav.interfaceVersion)

        val constraint = s.field("constraintHostBinder") as IConstraintHost
        assertEquals(6, constraint.getContentLimit(0))
        assertFalse(constraint.isAppDrivenRefreshEnabled)
        assertEquals(IConstraintHost.VERSION, constraint.interfaceVersion)

        val media = s.field("mediaHostBinder") as androidx.car.app.media.IMediaPlaybackHost
        media.registerMediaSessionToken(null)
        @Suppress("UNCHECKED_CAST")
        val token = HostSession::class.java.getDeclaredField("mediaToken")
            .apply { isAccessible = true }.get(s) as java.util.concurrent.atomic.AtomicReference<Boolean>
        assertFalse(token.get())
        media.registerMediaSessionToken(Bundleable.create("token"))
        assertTrue(token.get())

        val suggestion = s.field("suggestionHostBinder") as androidx.car.app.suggestion.ISuggestionHost
        suggestion.updateSuggestions(null)
        suggestion.updateSuggestions(Bundleable.create("s"))
    }

    @Test fun `task stack allows terminal templates past the quota`() {
        val stack = HostTaskStack()
        stack.record(HostTemplate.Pane(title = "a"))
        stack.record(HostTemplate.Grid())
        stack.record(HostTemplate.PlaceList())
        stack.record(HostTemplate.Search())
        stack.record(HostTemplate.Tabs())
        stack.record(HostTemplate.SignIn()) // depth 6 > quota 5
        assertFalse(stack.isAllowed(HostTemplate.Grid()))
        assertTrue(stack.isAllowed(HostTemplate.Pane(title = "end")))
        assertTrue(stack.isAllowed(HostTemplate.Message(title = "end")))
        assertTrue(stack.isAllowed(HostTemplate.Navigation()))
    }
}
