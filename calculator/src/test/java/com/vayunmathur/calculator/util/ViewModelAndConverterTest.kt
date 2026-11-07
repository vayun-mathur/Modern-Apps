package com.vayunmathur.calculator.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ViewModelAndConverterTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // selectCategory/swap refresh the units widget via WorkManager.
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        }
    }

    @Test fun viewModelDelegatesAndTogglesAngleMode() {
        val vm = CalculatorViewModel(app)
        assertEquals(AngleMode.RADIANS, vm.angleMode)
        vm.append("sin(0)")
        assertEquals("sin(0)", vm.calculatorUiState.input)
        vm.toggleAngleMode()
        assertEquals(AngleMode.DEGREES, vm.angleMode)
        assertEquals(AngleMode.DEGREES, vm.calculatorUiState.angleMode)
        assertEquals(AngleMode.DEGREES, vm.graphUiState.angleMode)
        vm.toggleAngleMode()
        assertEquals(AngleMode.RADIANS, vm.angleMode)
        assertEquals(1, vm.graphUiState.functions.size)
        assertTrue(vm.unitConverterUiState.categories.isNotEmpty())
    }

    @Test fun converterInitialConversion() {
        val tab = UnitConverterTab(app)
        val state = tab.uiState
        assertEquals(0, state.selectedCategoryIndex)
        assertEquals("1", state.inputText)
        // First category is Length; first two units convert 1 unit of [0] into [1].
        assertTrue(state.outputText.isNotEmpty(), state.outputText)
        assertFalse(state.isCurrencyCategory)
    }

    @Test fun converterInputAndSwap() {
        val tab = UnitConverterTab(app)
        tab.setConverterInput("100")
        assertEquals("100", tab.converterValueText)
        val before = tab.uiState.outputText
        assertTrue(before.isNotEmpty())
        val fromBefore = tab.converterFromToken
        val toBefore = tab.converterToToken
        tab.swapUnits()
        assertEquals(toBefore, tab.converterFromToken)
        assertEquals(fromBefore, tab.converterToToken)
    }

    @Test fun converterRejectsBadCategoryAndHandlesBadInput() {
        val tab = UnitConverterTab(app)
        tab.selectCategory(-1)
        assertEquals(0, tab.converterCategoryIndex)
        tab.selectCategory(9999)
        assertEquals(0, tab.converterCategoryIndex)
        tab.setConverterInput("not-a-number")
        assertEquals("", tab.uiState.outputText)
    }

    @Test fun converterSelectCategoryChangesUnits() {
        val tab = UnitConverterTab(app)
        tab.selectCategory(1)
        assertEquals(1, tab.converterCategoryIndex)
        val category = tab.uiState.categories[1]
        assertTrue(category.units.any { it.token == tab.converterFromToken })
        assertTrue(category.units.any { it.token == tab.converterToToken })
        tab.setFrom(category.units[0].token)
        tab.setTo(category.units.getOrElse(1) { category.units[0] }.token)
        assertTrue(tab.uiState.outputText.isNotEmpty())
    }

    @Test fun converterAdapterDelegates() {
        val tab = UnitConverterTab(app)
        val actions = converterActionAdapter(tab)
        actions.setConverterInput("42")
        assertEquals("42", tab.converterValueText)
        actions.selectCategory(2)
        assertEquals(2, tab.converterCategoryIndex)
        actions.swapUnits()
        assertEquals(2, tab.converterCategoryIndex)
    }

    @Test fun viewModelConverterStartLoadsAndFailsCurrencyGracefully() {
        // The VM starts the converter tab: it restores the persisted
        // selection and attempts a currency fetch, which fails offline
        // (no NetworkClient in tests) and must surface as an error state
        // on the Currency tab rather than crashing.
        val vm = CalculatorViewModel(app)
        val deadline = System.currentTimeMillis() + 10_000
        var state = vm.unitConverterUiState
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            state = vm.unitConverterUiState
            val currencyIndex = state.categories.lastIndex
            vm.selectCategory(currencyIndex)
            state = vm.unitConverterUiState
            if (state.currencyError != null) break
            Thread.sleep(20)
        }
        assertEquals("Couldn't load exchange rates", state.currencyError)
        assertTrue(state.isCurrencyCategory)
    }

    @Test fun currencyCategoryBuilding() {
        assertEquals(null, UnitRegistry.currencyCategory(emptyMap()))
        assertEquals(null, UnitRegistry.currencyCategory(mapOf("XXX" to 0.0)))
        val category = UnitRegistry.currencyCategory(mapOf("EUR" to 0.9, "USD" to 1.0, "JPY" to 150.0))
        assertEquals("Currency", category?.name)
        assertEquals(listOf("USD", "EUR", "JPY"), category?.units?.map { it.token })
        // 1 USD (factor 1) converts to 0.9 EUR through the base.
        val usd = category!!.units[0]
        val eur = category.units[1]
        assertEquals(0.9, eur.fromBase(usd.toBase(1.0)), 1e-9)
    }

    @Test fun keyNamesAreStable() {
        assertEquals("calculator_units_category", KEY_UNITS_CATEGORY)
        assertEquals("calculator_units_from_Length", unitsFromKey("Length"))
        assertEquals("calculator_units_to_Length", unitsToKey("Length"))
    }
}
