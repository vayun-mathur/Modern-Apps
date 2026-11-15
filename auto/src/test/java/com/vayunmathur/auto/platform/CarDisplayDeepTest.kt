package com.vayunmathur.auto.platform

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.library.carhost.HostNavState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep [CarDisplay] + [CarDisplayInput] coverage without showing a
 * presentation: every late-wired callback setter, cached-feed pushes,
 * config no-ops, and the full touch/key/scroll dispatch against a fake
 * decor view.
 */
@RunWith(RobolectricTestRunner::class)
class CarDisplayDeepTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @BeforeTest fun reset() {
        CarLauncherState.setDrivingRestricted(false)
        CarLauncherState.setNight(false)
        CarLauncherState.setActiveCall(null)
        CarLauncherState.selectApp(null)
        CarLauncherState.setNowPlaying(
            NowPlayingInfo(title = null, artist = null, playing = false, positionMs = 0, durationMs = null),
        )
        CarLauncherState.hideNowPlaying()
    }

    private fun display() = CarDisplay(ctx, 800, 480, 160)

    @Test fun `every late-wired transport and call callback is stored`() {
        val d = display()
        var media = 0; var prev = 0; var next = 0
        var answer = 0; var end = 0; var hold = 0; var mute = 0
        d.onMediaTap = { media++ }
        d.onPreviousTap = { prev++ }
        d.onNextTap = { next++ }
        d.onAnswerCall = { answer++ }
        d.onEndCall = { end++ }
        d.onHoldToggle = { hold++ }
        d.onMuteToggle = { mute++ }
        d.onMediaTap?.invoke(); d.onPreviousTap?.invoke(); d.onNextTap?.invoke()
        d.onAnswerCall?.invoke(); d.onEndCall?.invoke(); d.onHoldToggle?.invoke(); d.onMuteToggle?.invoke()
        assertEquals(1, media); assertEquals(1, prev); assertEquals(1, next)
        assertEquals(1, answer); assertEquals(1, end); assertEquals(1, hold); assertEquals(1, mute)
        var forwarded: Triple<Int, Float, Float>? = null
        d.mapTouchForwarder = { a, x, y -> forwarded = Triple(a, x, y); true }
        assertTrue(d.mapTouchForwarder!!.invoke(0, 3f, 4f))
        assertEquals(Triple(0, 3f, 4f), forwarded)
        idleMain()
        d.release(); idleMain()
    }

    @Test fun `cached feeds publish and hide without a presentation`() {
        val d = display()
        val info = NowPlayingInfo(title = "A", artist = null, playing = true, positionMs = 0, durationMs = null)
        d.setNowPlaying(info)
        assertEquals(info, CarLauncherState.nowPlaying.value)
        // hideNowPlaying drops a snapshot that should show (the card hides);
        // an idle snapshot is already hidden and stays cached.
        d.hideNowPlaying()
        assertNull(CarLauncherState.nowPlaying.value)
        val idle = NowPlayingInfo(title = null, artist = null, playing = false, positionMs = 0, durationMs = null)
        d.setNowPlaying(idle)
        d.hideNowPlaying()
        assertEquals(idle, CarLauncherState.nowPlaying.value)
        d.setNavSnapshot(NavSnapshot(latitudeE7 = 10, longitudeE7 = 20, parked = true))
        d.setHostNavState(HostNavState(navigating = true, cue = "Turn left"))
        assertEquals("Turn left", CarLauncherState.hostNav.value?.cue)
        var surfaceSeen = -1
        d.setMapSurfaceListener { _, w, _ -> surfaceSeen = w }
        CarLauncherState.mapSurfaceListener?.invoke(null, 640, 480)
        assertEquals(640, surfaceSeen)
        idleMain()
        d.release(); idleMain()
    }

    @Test fun `config change without a cached surface is a no-op and release is idempotent`() {
        val d = display()
        d.updateConfig(1024, 600, 240) // new size, but no surface was ever shown
        idleMain()
        d.startFrameInvalidation(0) // coerced rate, no presentation: loop never arms
        d.startFrameInvalidation(60)
        idleMain()
        d.stopFrameInvalidation()
        idleMain()
        d.release()
        d.release()
        idleMain()
    }

    @Test fun `tap bounds reject outside points on every edge`() {
        val b = CarDisplay.TapBounds(10, 20, 110, 120)
        assertFalse(b.contains(9f, 60f))
        assertFalse(b.contains(111f, 60f))
        assertFalse(b.contains(60f, 19f))
        assertFalse(b.contains(60f, 121f))
        assertTrue(b.contains(60f, 70f))
    }

    // -- CarDisplayInput -------------------------------------------------------
    private class FakeView(context: android.content.Context) : View(context) {
        val touches = mutableListOf<Int>()
        val keys = mutableListOf<Pair<Int, Int>>()
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touches += event.actionMasked
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            keys += event.keyCode to event.action
            return true
        }
    }

    private fun input(
        view: View?,
        bounds: CarDisplay.TapBounds? = null,
        onTap: () -> Unit = {},
    ) = CarDisplayInput(
        mainHandler = Handler(Looper.getMainLooper()),
        decorView = { view },
        cardBounds = { bounds },
        onCardTap = onTap,
    )

    @Test fun `input without a decor view drops touch key and scroll`() {
        val input = input(null)
        assertFalse(input.injectTouch(MotionEvent.ACTION_DOWN, listOf(Triple(1f, 2f, 0)), 0))
        assertFalse(input.injectKey(4, true))
        assertFalse(input.injectScroll(1))
        assertFalse(input.handleCarTap(1f, 1f))
    }

    @Test fun `input dispatches touch key and scroll to the decor view`() {
        val view = FakeView(ctx)
        val input = input(view)
        assertTrue(input.injectTouch(MotionEvent.ACTION_DOWN, listOf(Triple(10f, 10f, 0)), 0))
        assertTrue(input.injectTouch(MotionEvent.ACTION_UP, listOf(Triple(10f, 10f, 0)), 0))
        assertTrue(input.injectKey(24, true))
        assertTrue(input.injectKey(24, false))
        assertTrue(input.injectScroll(-3))
        idleMain()
        assertTrue(view.touches.contains(MotionEvent.ACTION_DOWN))
        assertTrue(view.touches.contains(MotionEvent.ACTION_UP))
        assertTrue(view.touches.contains(MotionEvent.ACTION_SCROLL))
        assertEquals(
            listOf(24 to KeyEvent.ACTION_DOWN, 24 to KeyEvent.ACTION_UP),
            view.keys,
        )
    }

    @Test fun `card tap hit consumes the touch and fires the toggle`() {
        val view = FakeView(ctx)
        var taps = 0
        val input = input(view, bounds = CarDisplay.TapBounds(0, 0, 200, 100), onTap = { taps++ })
        assertFalse(input.handleCarTap(500f, 500f)) // outside the card
        assertTrue(input.handleCarTap(50f, 50f))
        idleMain()
        assertEquals(1, taps)
        // A DOWN inside the card is consumed as a tap, never dispatched as a gesture.
        assertTrue(input.injectTouch(MotionEvent.ACTION_DOWN, listOf(Triple(50f, 50f, 0)), 0))
        idleMain()
        assertEquals(2, taps)
        assertFalse(view.touches.contains(MotionEvent.ACTION_DOWN))
        // Multi-pointer frames never take the card shortcut.
        assertTrue(
            input.injectTouch(
                MotionEvent.ACTION_POINTER_DOWN,
                listOf(Triple(50f, 50f, 0), Triple(60f, 60f, 1)),
                actionIndex = 1,
            ),
        )
        idleMain()
        assertEquals(2, taps)
    }
}
