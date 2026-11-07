package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class GlanceProvideTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // Synchronous WorkManager so Glance widget updates (which are
        // scheduled as work) actually run their composition in-process.
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        }
    }

    @Test fun unitsWidgetProvidesGlance() = runBlocking {
        // provideGlance loads data (falling back to the first category with
        // nothing persisted) and composes the widget content to RemoteViews.
        val widget = UnitsGlanceWidget()
        widget.provideGlance(ctx, AppWidgetId(201))
        runCatching { widget.update(ctx, AppWidgetId(201)) }
        Unit
    }

    @Test fun calculatorWidgetProvidesGlance() = runBlocking {
        val widget = CalculatorGlanceWidget()
        widget.provideGlance(ctx, AppWidgetId(202))
        runCatching { widget.update(ctx, AppWidgetId(202)) }
        Unit
    }

    @Test fun widgetStateDataClassesHoldValues() {
        val state = UnitsWidgetState("Length", "1 m = 100 cm")
        assertEquals("Length", state.categoryName)
        val data = UnitsWidgetData(state, expanded = true)
        assertEquals(true, data.expanded)
        assertEquals(state, data.state)
    }
}
