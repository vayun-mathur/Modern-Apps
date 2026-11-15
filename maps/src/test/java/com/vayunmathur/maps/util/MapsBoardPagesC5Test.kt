package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 departure-board paging: the walk advances only on a full page
 * whose last event precedes the window end, and stops otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsBoardPagesC5Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun departure(depSecs: Int) = OfflineRouter.RawDeparture(
        routeName = "N",
        headsign = "Downtown",
        feed = "sfmta",
        stopCode = "S1",
        routeColor = 0,
        routeType = 0,
        depSecs = depSecs,
        delaySecs = 0,
        cancelled = false,
        realTime = false,
        tripId = 0L,
    )

    private fun pagesClass(): Class<*> =
        Class.forName("com.vayunmathur.maps.util.OfflineRouterTransit\$BoardPages")

    private fun newPages(max: Int, until: Instant?): Any {
        val clazz = pagesClass()
        val ctor = clazz.getDeclaredConstructor(
            Context::class.java,
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Instant::class.java,
            Instant::class.java,
            String::class.java,
            String::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            ctx, "/tmp/c5-none", "c5feed", 37.8, -122.4, max,
            Instant.ofEpochMilli(0), until, null, null,
        )
    }

    private fun advance(pages: Any, raw: Array<OfflineRouter.RawDeparture>): Boolean {
        val clock = transitClock(null) { zone -> Instant.ofEpochMilli(0).atZone(zone) }
        val method = pagesClass().getDeclaredMethod(
            "advance",
            Array<OfflineRouter.RawDeparture>::class.java,
            TransitClock::class.java,
        )
        method.isAccessible = true
        return method.invoke(pages, raw, clock) as Boolean
    }

    @Test
    fun `board walk stops without an until window`() {
        val pages = newPages(max = 2, until = null)
        assertEquals(false, advance(pages, arrayOf(departure(100), departure(200))))
    }

    @Test
    fun `board walk stops on a short page`() {
        val pages = newPages(max = 5, until = Instant.ofEpochMilli(3_600_000))
        assertEquals(false, advance(pages, arrayOf(departure(100))))
    }

    @Test
    fun `board walk stops when the last event reaches the window end`() {
        val pages = newPages(max = 1, until = Instant.ofEpochMilli(-86_400_000))
        assertEquals(false, advance(pages, arrayOf(departure(100))))
    }

    @Test
    fun `board walk advances on a full page before the window end`() {
        val pages = newPages(max = 1, until = Instant.ofEpochMilli(3_600_000))
        assertTrue(advance(pages, arrayOf(departure(100))))
    }
}
