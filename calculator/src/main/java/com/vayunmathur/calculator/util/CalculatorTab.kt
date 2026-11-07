package com.vayunmathur.calculator.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The keypad tab: input, live preview, history, memory and output-unit selection.
 *
 * Extracted from [CalculatorViewModel] so the ViewModel stays under detekt's function cap;
 * the ViewModel delegates [CalculatorActions] to this and exposes [uiState].
 */
class CalculatorTab {
    /** The trig mode evaluations use; synced from the ViewModel whenever it changes. */
    var angleMode: AngleMode = AngleMode.RADIANS

    var input by mutableStateOf("")
        private set

    /** Live-evaluated preview of [input]; empty when blank or invalid. Unit-aware. */
    var preview by mutableStateOf("")
        private set

    /** The last successfully computed value, exposed to expressions as `ans`. */
    var lastAnswer by mutableStateOf(0.0)
        private set

    /** The single memory register (M+ / M- / MR / MC). */
    var memory by mutableStateOf(0.0)
        private set

    /** Units the current result can be shown in; empty when the result is dimensionless. */
    var unitOptions by mutableStateOf<List<UnitDef>>(emptyList())
        private set

    /** The unit [preview] is currently rendered in. */
    var selectedUnit by mutableStateOf<UnitDef?>(null)
        private set

    /** The most recent parsed preview value, kept so switching output units is instant. */
    private var previewQuantity: Quantity? = null

    val history = mutableStateListOf<HistoryEntry>()

    /**
     * Snapshot of everything the keypad screen draws. Read during composition, so the
     * underlying `mutableStateOf` reads are tracked normally.
     */
    val uiState: CalculatorUiState
        get() = CalculatorUiState(
            input = input,
            preview = preview,
            memory = memory,
            angleMode = angleMode,
            history = history,
            unitOptions = unitOptions,
            selectedUnit = selectedUnit,
            unitCategories = UnitRegistry.categories,
        )

    fun updateInput(value: String) {
        input = value
        recomputePreview()
    }

    fun append(text: String) = updateInput(input + text)

    fun insertInstant(epochSeconds: Long) = append("#$epochSeconds")

    fun backspace() {
        if (input.isNotEmpty()) updateInput(input.dropLast(1))
    }

    fun clear() = updateInput("")

    /** Evaluate the current input, push it to history, store `ans`, show the result. */
    fun evaluate() {
        if (input.isBlank()) return
        val quantity = try {
            Expression.parse(input).evalQuantity(angle = angleMode, ans = lastAnswer)
        } catch (ignored: ExpressionError) {
            return // leave input untouched; the preview already flagged the problem
        }
        if (quantity.value.isNaN()) return
        val isInstant = quantity.instant
        val unit = if (quantity.isDimensionless || isInstant) {
            null
        } else {
            selectedUnit ?: UnitRegistry.defaultUnitFor(quantity.dimension)
        }
        val display = formatQuantity(quantity, unit)
        // The token form re-parses, so the result can seed the next calculation.
        val token = formatQuantity(quantity, unit, useToken = true)
        history.add(0, HistoryEntry(input, display, token))
        lastAnswer = quantity.value
        if (unit != null) selectedUnit = unit
        updateInput(token)
    }

    fun useHistory(entry: HistoryEntry) = updateInput(entry.resultToken)

    fun selectOutputUnit(token: String) {
        val unit = unitOptions.firstOrNull { it.token == token } ?: return
        selectedUnit = unit
        previewQuantity?.let { preview = formatQuantity(it, unit) }
    }

    // Memory register operations. M+/M- fold the current preview (or input) into memory.
    fun memoryClear() { memory = 0.0 }
    fun memoryRecall() = append(formatResultToken(memory))
    fun memoryAdd() { currentValue()?.let { memory += it } }
    fun memorySubtract() { currentValue()?.let { memory -= it } }

    /** Re-derive [preview], [unitOptions] and [selectedUnit] from the current [input]. */
    fun refresh() = recomputePreview()

    private fun currentValue(): Double? = try {
        Expression.parse(input.ifBlank { "0" }).eval(angle = angleMode, ans = lastAnswer)
            .takeIf { !it.isNaN() }
    } catch (ignored: ExpressionError) { null }

    /** Re-derive [preview], [unitOptions] and [selectedUnit] from the current [input]. */
    private fun recomputePreview() {
        val quantity = try {
            if (input.isBlank()) null
            else Expression.parse(input).evalQuantity(angle = angleMode, ans = lastAnswer)
                .takeIf { !it.value.isNaN() }
        } catch (ignored: ExpressionError) {
            null
        }
        previewQuantity = quantity
        if (quantity == null || quantity.isDimensionless) {
            unitOptions = emptyList()
            selectedUnit = null
            preview = if (quantity == null) "" else formatResult(quantity.value)
        } else if (quantity.instant) {
            // A date/datetime has no unit chips; it renders as localized text.
            unitOptions = emptyList()
            selectedUnit = null
            preview = formatInstant(quantity.value)
        } else {
            val options = UnitRegistry.unitsFor(quantity.dimension)
            unitOptions = options
            if (selectedUnit !in options) selectedUnit = UnitRegistry.defaultUnitFor(quantity.dimension)
            preview = formatQuantity(quantity, selectedUnit)
        }
    }
}

/** Adapts [CalculatorTab] to the [CalculatorActions] interface for ViewModel delegation. */
internal fun calculatorActionAdapter(tab: CalculatorTab): CalculatorActions = object : CalculatorActions {
    override fun append(text: String) = tab.append(text)
    override fun insertInstant(epochSeconds: Long) = tab.insertInstant(epochSeconds)
    override fun clear() = tab.clear()
    override fun backspace() = tab.backspace()
    override fun evaluate() = tab.evaluate()
    override fun memoryClear() = tab.memoryClear()
    override fun memoryRecall() = tab.memoryRecall()
    override fun memoryAdd() = tab.memoryAdd()
    override fun memorySubtract() = tab.memorySubtract()
    override fun useHistory(entry: HistoryEntry) = tab.useHistory(entry)
    override fun selectOutputUnit(token: String) = tab.selectOutputUnit(token)
}
