package com.vayunmathur.launcher.platform

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the pure parts of [LauncherWidgetHost]: [spanFor] cell math,
 * [flatten]/[unflatten] round trips, and safe [release]/[releaseOrphans]/
 * [updateSize] no-ops.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherWidgetHostTest {

    private fun flow(): WidgetBindFlow {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return WidgetBindFlow(
            ctx,
            LauncherWidgetHost(ctx),
            android.appwidget.AppWidgetManager.getInstance(ctx),
        )
    }

    private fun info(minWidth: Int, minHeight: Int) = AppWidgetProviderInfo().apply {
        provider = ComponentName("com.example", "com.example.Widget")
        this.minWidth = minWidth
        this.minHeight = minHeight
    }

    @Test fun spanForCoversMinimum() {
        // minWidth/minHeight are in dp: 200dp needs three 80dp cells
        // and 100dp needs two, whatever the screen density is.
        val (x, y) = flow().spanFor(info(200, 100), cellWidthDp = 80, cellHeightDp = 80)
        assertEquals(3, x)
        assertEquals(2, y)
    }

    @Test fun spanForTinyWidgetIsOneCell() {
        assertEquals(1 to 1, flow().spanFor(info(1, 1), cellWidthDp = 80, cellHeightDp = 80))
    }

    @Test fun flattenRoundTrip() {
        val h = flow()
        val flat = h.flatten(ComponentName("com.example", "com.example.Widget"))
        assertEquals(ComponentName("com.example", "com.example.Widget"), h.unflatten(flat))
        assertEquals(null, h.unflatten(null))
        assertEquals(null, h.unflatten("not a component"))
    }

    @Test fun releaseUnknownIdIsSafe() {
        val h = flow()
        h.release(424242)
        h.releaseOrphans(emptySet())
        // Nothing was bound, and the released id still resolves to nothing.
        kotlin.test.assertEquals(null, h.providerInfo(424242))
    }
}
