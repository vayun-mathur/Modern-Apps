package com.vayunmathur.screentime.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SpentState's per-day scratch: marks accumulate, clear removes one, and a
 * stale day stamp prunes everything (the midnight reset).
 */
@RunWith(RobolectricTestRunner::class)
class SpentStateTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun markClearAndPrune() {
        val spent = SpentState(ctx)
        spent.pruneToToday()
        assertFalse(spent.hasSpent("com.example.a"))
        spent.markSpent("com.example.a")
        spent.markSpent("com.example.b")
        assertTrue(spent.hasSpent("com.example.a"))
        assertTrue(spent.hasSpent("com.example.b"))
        spent.clear("com.example.a")
        assertFalse(spent.hasSpent("com.example.a"))
        assertTrue(spent.hasSpent("com.example.b"))

        // Simulate yesterday's stamp: the next prune wipes the marks.
        val prefs = ctx.getSharedPreferences("screentime_spent", Context.MODE_PRIVATE)
        prefs.edit().putString("day", "2000-01-01").apply()
        spent.pruneToToday()
        assertFalse(spent.hasSpent("com.example.b"))
        assertEquals(
            java.time.LocalDate.now().toString(),
            prefs.getString("day", null),
        )
        spent.clear("com.example.b")
    }
}
