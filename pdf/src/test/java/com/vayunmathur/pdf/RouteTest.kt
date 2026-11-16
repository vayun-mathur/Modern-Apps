package com.vayunmathur.pdf

import android.content.Intent
import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RouteTest {

    @Test
    fun `view intent routes to viewer and plain launch routes home`() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("content://docs/report.pdf"))
        assertEquals(Route.Viewer("content://docs/report.pdf"), MainActivity.routeFor(view))
        assertEquals(Route.Home, MainActivity.routeFor(Intent(Intent.ACTION_MAIN)))
        assertEquals(Route.Home, MainActivity.routeFor(null))
    }

    @Test
    fun `capture destinations carry the page index`() {
        assertEquals(3, Route.CapturePage(3).index)
        assertEquals(3, Route.Crop(3).index)
    }
}
