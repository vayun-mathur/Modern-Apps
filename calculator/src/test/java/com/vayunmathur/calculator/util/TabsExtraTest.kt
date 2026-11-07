package com.vayunmathur.calculator.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TabsExtraTest {

    // ---- CalculatorTab: units, memory, instants ----

    @Test fun unitBearingPreviewOffersOutputUnits() {
        val tab = CalculatorTab()
        tab.updateInput("2km")
        assertTrue(tab.unitOptions.isNotEmpty())
        assertEquals("m", tab.selectedUnit?.token)
        assertTrue(tab.preview.startsWith("2000"), tab.preview)
    }

    @Test fun selectOutputUnitReformatsPreview() {
        val tab = CalculatorTab()
        tab.updateInput("2km")
        tab.selectOutputUnit("cm")
        assertEquals("cm", tab.selectedUnit?.token)
        assertTrue(tab.preview.startsWith("200000"), tab.preview)
        // Unknown tokens are ignored.
        tab.selectOutputUnit("furlong")
        assertEquals("cm", tab.selectedUnit?.token)
    }

    @Test fun evaluateWithUnitsSeedsTokenForm() {
        val tab = CalculatorTab()
        tab.updateInput("2km")
        tab.evaluate()
        assertEquals(2000.0, tab.lastAnswer)
        assertTrue(tab.history.size == 1)
    }

    @Test fun memoryRegisterLifecycle() {
        val tab = CalculatorTab()
        tab.updateInput("5")
        tab.memoryAdd()
        assertEquals(5.0, tab.memory)
        tab.memoryAdd()
        assertEquals(10.0, tab.memory)
        tab.memorySubtract()
        assertEquals(5.0, tab.memory)
        tab.clear()
        tab.memoryRecall()
        assertEquals("5", tab.input)
        tab.memoryClear()
        assertEquals(0.0, tab.memory)
    }

    @Test fun memoryOpsOnInvalidInputAreNoOps() {
        val tab = CalculatorTab()
        tab.updateInput("1+")
        tab.memoryAdd()
        assertEquals(0.0, tab.memory)
    }

    @Test fun insertInstantAndUseHistory() {
        val tab = CalculatorTab()
        tab.insertInstant(1_700_000_000)
        assertEquals("#1700000000", tab.input)
        assertTrue(tab.preview.isNotEmpty())
        tab.useHistory(HistoryEntry("x", "42"))
        assertEquals("42", tab.input)
    }

    @Test fun backspaceOnEmptyStaysEmpty() {
        val tab = CalculatorTab()
        tab.backspace()
        assertEquals("", tab.input)
        tab.append("12")
        tab.backspace()
        assertEquals("1", tab.input)
    }

    @Test fun adaptersDelegateToTab() {
        val tab = CalculatorTab()
        val actions = calculatorActionAdapter(tab)
        actions.append("3+4")
        actions.evaluate()
        assertEquals(7.0, tab.lastAnswer)
        actions.backspace()
        actions.clear()
        assertEquals("", tab.input)
        actions.insertInstant(10)
        assertEquals("#10", tab.input)
        actions.memoryClear()
        actions.useHistory(HistoryEntry("e", "9"))
        assertEquals("9", tab.input)
    }

    @Test fun uiStateMirrorsTab() {
        val tab = CalculatorTab()
        tab.updateInput("1+1")
        val state = tab.uiState
        assertEquals("1+1", state.input)
        assertEquals("2", state.preview)
        assertEquals(UnitRegistry.categories, state.unitCategories)
    }

    // ---- GraphTab ----

    @Test fun graphFunctionCrud() {
        val tab = GraphTab()
        assertEquals(1, tab.functions.size)
        assertEquals("x", tab.functions[0].text)
        tab.addFunction()
        assertEquals(2, tab.functions.size)
        val id = tab.functions[1].id
        tab.updateFunction(id, "x^2")
        assertEquals("x^2", tab.functions[1].text)
        tab.toggleFunction(id)
        assertEquals(false, tab.functions[1].enabled)
        tab.togglePolar(id)
        assertEquals(true, tab.functions[1].polar)
        tab.removeFunction(id)
        assertEquals(1, tab.functions.size)
    }

    @Test fun removingLastFunctionAddsAFreshOne() {
        val tab = GraphTab()
        tab.removeFunction(tab.functions[0].id)
        assertEquals(1, tab.functions.size)
        assertEquals("", tab.functions[0].text)
    }

    @Test fun mutatingUnknownIdIsNoOp() {
        val tab = GraphTab()
        tab.updateFunction(999, "y")
        tab.toggleFunction(999)
        assertEquals("x", tab.functions[0].text)
        assertEquals(true, tab.functions[0].enabled)
    }

    @Test fun graphViewportAndMarkersViaAdapter() {
        val tab = GraphTab()
        val actions = graphActionAdapter(tab)
        actions.setViewSize(800f, 600f)
        assertEquals(800f, tab.viewWidthPx)
        actions.setViewport(GraphViewport(centerX = 1.0, centerY = 2.0, scale = 30.0))
        assertEquals(1.0, tab.centerX)
        assertEquals(30.0, tab.scale)
        // Tap far from any feature on y=x: either a marker appears near the
        // origin feature or none does; tapping again must not crash.
        actions.tapGraph(GraphPoint(0.0, 0.0), 0.5)
        val markerCount = tab.markers.size
        assertTrue(markerCount <= 1)
        if (markerCount == 1) {
            actions.tapGraph(tab.markers[0].point, 0.5)
            assertEquals(0, tab.markers.size)
        }
        actions.addFunction()
        actions.updateFunction(tab.functions[1].id, "x")
        actions.togglePolar(tab.functions[1].id)
        actions.removeFunction(tab.functions[1].id)
    }

    @Test fun graphUiStateReflectsFunctions() {
        val tab = GraphTab()
        tab.angleMode = AngleMode.DEGREES
        val state = tab.uiState
        assertEquals(AngleMode.DEGREES, state.angleMode)
        assertEquals(tab.functions.toList(), state.functions)
        assertEquals(GraphViewport(0.0, 0.0, 60.0), state.viewport)
    }

    // ---- GraphViewport ----

    @Test fun viewportTransformsAreInverse() {
        val vp = GraphViewport(centerX = 2.0, centerY = -1.0, scale = 50.0)
        val gx = vp.graphX(123f, 800f)
        assertEquals(123.0, vp.screenX(gx, 800f).toDouble(), 1e-3)
        val gy = vp.graphY(45f, 600f)
        assertEquals(45.0, vp.screenY(gy, 600f).toDouble(), 1e-3)
        val bounds = vp.bounds(800f, 600f)
        assertTrue(bounds.xMin < bounds.xMax && bounds.yMin < bounds.yMax)
        assertEquals(vp.graphX(0f, 800f), bounds.xMin)
    }

    @Test fun viewportZoomClampsScale() {
        val vp = GraphViewport(scale = 60.0)
        val zoomed = vp.transformed(400f, 300f, 0f, 0f, 1e9f, 800f, 600f)
        assertEquals(400000.0, zoomed.scale)
        val out = vp.transformed(400f, 300f, 0f, 0f, 1e-9f, 800f, 600f)
        assertEquals(2.0, out.scale)
        assertNotEquals(vp.centerX, vp.transformed(400f, 300f, 10f, 0f, 1f, 800f, 600f).centerX)
    }
}
