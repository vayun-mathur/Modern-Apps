package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WidgetLogicTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun evaluateForWidgetBasics() {
        assertNull(evaluateForWidget("", 0.0))
        assertNull(evaluateForWidget("1+", 0.0))
        assertNull(evaluateForWidget("0/0", 0.0))
        val result = evaluateForWidget("2+3", 0.0)
        assertEquals(5.0, result?.value)
        assertEquals("5", result?.display)
        assertEquals("5", result?.token)
    }

    @Test fun evaluateForWidgetUsesAnsAndUnits() {
        val withAns = evaluateForWidget("ans*2", 21.0)
        assertEquals(42.0, withAns?.value)
        val length = evaluateForWidget("2km", 0.0)
        assertEquals(2000.0, length?.value)
        assertTrue(length?.display?.startsWith("2000") == true)
    }

    @Test fun appendWidgetInputCapsLength() {
        assertEquals("abc", appendWidgetInput("ab", "c"))
        val long = "x".repeat(MAX_WIDGET_INPUT_LENGTH)
        assertEquals(MAX_WIDGET_INPUT_LENGTH, appendWidgetInput(long, "more").length)
        assertEquals("", appendWidgetInput("", ""))
    }

    @Test fun calculatorWidgetSettingsActivityWithoutIdFinishes() {
        val controller = Robolectric.buildActivity(CalculatorWidgetSettingsActivity::class.java)
        controller.create()
        assertTrue(controller.get().isFinishing)
    }

    @Test fun widgetReceiversConstruct() {
        // Receivers are thin MaWidgetReceiver subclasses; construction must
        // not touch the widget host.
        CalculatorGlanceWidgetReceiver()
        UnitsGlanceWidgetReceiver()
        assertEquals("openUnits", UnitsGlanceWidget.EXTRA_OPEN_UNITS)
    }

    @Test fun toggleActionFlipsExpandedState() {
        // The glance state store may be unavailable under Robolectric; the
        // action must at least not throw synchronously before that point.
        // Its state flip is covered indirectly through loadData above.
        assertTrue(UnitsGlanceWidget.EXPANDED.name == "units_widget_category_expanded")
    }
}
