package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class WidgetActionsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // Glance widget updates are scheduled through WorkManager; the
        // synchronous test WorkManager lets them complete inside runBlocking
        // instead of suspending forever on a background executor that a
        // previous test class may have torn down.
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        }
        // Another test class in this JVM (the converter tab tests) may
        // already hold a DataStore instance for the shared preferences
        // file; a second instance's edit then waits on that instance
        // forever. Unlink the file so this class's instance starts fresh.
        java.io.File(ctx.filesDir, "datastore/datastore_default.preferences_pb").delete()
        // Leftover Glance work enqueued by an earlier test class would be
        // picked up by this class's synchronous WorkManager and its session
        // can suspend the widget update below forever; drain it first.
        runCatching {
            androidx.work.WorkManager.getInstance(ctx).cancelAllWork().result.get()
        }
    }

    private suspend fun inputOf(id: AppWidgetId): String =
        getAppWidgetState(ctx, PreferencesGlanceStateDefinition, id)[CalculatorInputKey].orEmpty()

    @Test fun calculatorKeyActionAppendEvaluateClear() = runBlocking {
        val action = CalculatorKeyAction()
        val id = AppWidgetId(101)
        action.onAction(ctx, id, actionParametersOf(CalculatorKeyParam to "append:5"))
        assertEquals("5", inputOf(id))
        action.onAction(ctx, id, actionParametersOf(CalculatorKeyParam to "append:+3"))
        assertEquals("5+3", inputOf(id))
        action.onAction(ctx, id, actionParametersOf(CalculatorKeyParam to COMMAND_EVALUATE))
        assertEquals("8", inputOf(id))
        action.onAction(ctx, id, actionParametersOf(CalculatorKeyParam to COMMAND_BACKSPACE))
        assertEquals("", inputOf(id))
        action.onAction(ctx, id, actionParametersOf(CalculatorKeyParam to "append:9"))
        action.onAction(ctx, id, actionParametersOf(CalculatorKeyParam to COMMAND_CLEAR))
        assertEquals("", inputOf(id))
    }

    @Test fun calculatorKeyActionWithoutCommandLeavesInput() = runBlocking {
        val id = AppWidgetId(102)
        CalculatorKeyAction().onAction(ctx, id, actionParametersOf(CalculatorKeyParam to "append:1"))
        CalculatorKeyAction().onAction(ctx, id, actionParametersOf())
        assertEquals("1", inputOf(id))
    }

    @Test fun toggleActionFlipsExpandedState() = runBlocking {
        val id = AppWidgetId(103)
        ToggleUnitsCategoryListAction().onAction(ctx, id, actionParametersOf())
        assertEquals(
            true,
            getAppWidgetState(ctx, PreferencesGlanceStateDefinition, id)[UnitsGlanceWidget.EXPANDED],
        )
        ToggleUnitsCategoryListAction().onAction(ctx, id, actionParametersOf())
        assertEquals(
            false,
            getAppWidgetState(ctx, PreferencesGlanceStateDefinition, id)[UnitsGlanceWidget.EXPANDED],
        )
    }
}
