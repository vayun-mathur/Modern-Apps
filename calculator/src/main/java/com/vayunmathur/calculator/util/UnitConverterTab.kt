package com.vayunmathur.calculator.util

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.calculator.widget.UnitsGlanceWidget
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.widgets.updateWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Not private: the units widget reads these same preferences, and a second copy of the
// key names is a copy that can drift.
internal const val KEY_UNITS_CATEGORY = "calculator_units_category"

internal fun unitsFromKey(categoryName: String) = "calculator_units_from_$categoryName"

internal fun unitsToKey(categoryName: String) = "calculator_units_to_$categoryName"

/**
 * The units-converter tab: category, from/to units, value, and live currency rates.
 *
 * Extracted from [CalculatorViewModel] so the ViewModel stays under detekt's function cap;
 * the ViewModel delegates [UnitConverterActions] to this and exposes [uiState]. Call
 * [start] once the ViewModel's scope is available.
 */
class UnitConverterTab(
    private val application: Application,
    // Test seam: the currency-rate fetch, so unit tests can force the
    // offline path instead of depending on the ambient network being
    // unreachable (on a box with internet the old test's "fails offline"
    // fetch succeeded and no error state ever appeared).
    private val ratesFetcher: suspend () -> CurrencyRatesDto = { CurrencyApi.rates() },
) {
    private val dataStore = DataStoreUtils.getInstance(application)
    private var scope: CoroutineScope? = null

    var converterCategoryIndex by mutableStateOf(0)
        private set
    var converterFromToken by mutableStateOf(UnitRegistry.categories[0].units[0].token)
        private set
    var converterToToken by mutableStateOf(
        UnitRegistry.categories[0].units.getOrElse(1) { UnitRegistry.categories[0].units[0] }.token,
    )
        private set
    var converterValueText by mutableStateOf("1")
        private set

    // Live currency rates power a "Currency" tab appended after the static categories. The tab
    // is always present (so it stays reachable while loading or after a failure); its units
    // populate once rates are fetched.
    private var currencyCategory by mutableStateOf<UnitCategory?>(null)
    private var currencyLoading by mutableStateOf(false)
    private var currencyError by mutableStateOf<String?>(null)

    /**
     * The pair last converted in each category, keyed by category name. Mirrors what is persisted
     * so switching tabs never has to touch the (asynchronously hydrated) preference snapshot.
     */
    private val lastUnits = mutableMapOf<String, Pair<String, String>>()

    /** Static physical-unit categories plus the (possibly still-empty) live Currency tab. */
    private val converterCategories: List<UnitCategory>
        get() = UnitRegistry.categories + (currencyCategory ?: EMPTY_CURRENCY_CATEGORY)

    private val currencyIndex: Int get() = converterCategories.lastIndex

    val uiState: UnitConverterUiState
        get() {
            val categories = converterCategories
            val index = converterCategoryIndex.coerceIn(categories.indices)
            return UnitConverterUiState(
                categories = categories,
                selectedCategoryIndex = index,
                fromToken = converterFromToken,
                toToken = converterToToken,
                inputText = converterValueText,
                outputText = convert(categories[index]),
                currencyLoading = currencyLoading,
                currencyError = currencyError,
                isCurrencyCategory = index == currencyIndex,
            )
        }

    /** Bind the ViewModel's scope, then load persisted selection and live rates. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        loadCurrencyRates()
        restoreConverterSelection()
    }

    private fun convert(category: UnitCategory): String {
        val from = category.units.firstOrNull { it.token == converterFromToken } ?: return ""
        val to = category.units.firstOrNull { it.token == converterToToken } ?: return ""
        val value = converterValueText.toDoubleOrNull() ?: return ""
        return formatResult(to.fromBase(from.toBase(value)))
    }

    fun selectCategory(index: Int) {
        val categories = converterCategories
        if (index !in categories.indices) return
        converterCategoryIndex = index
        applyRememberedUnits(categories[index])
        persist(KEY_UNITS_CATEGORY, categories[index].name)
        refreshUnitsWidget()
    }

    /**
     * Restores the pair the user last converted in [category], falling back to its first two units.
     * A remembered token is dropped if the category no longer offers it, which happens when a
     * currency disappears from the rate list.
     */
    private fun applyRememberedUnits(category: UnitCategory) {
        val units = category.units
        val remembered = rememberedUnits(category.name)
        val defaultFrom = units.getOrNull(0)?.token ?: ""
        val defaultTo = units.getOrElse(1) { units.getOrNull(0) }?.token ?: defaultFrom
        converterFromToken = remembered?.first?.takeIf { token -> units.any { it.token == token } }
            ?: defaultFrom
        converterToToken = remembered?.second?.takeIf { token -> units.any { it.token == token } }
            ?: defaultTo
    }

    private fun rememberedUnits(categoryName: String): Pair<String, String>? {
        lastUnits[categoryName]?.let { return it }
        val from = dataStore.getString(unitsFromKey(categoryName)) ?: return null
        val to = dataStore.getString(unitsToKey(categoryName)) ?: return null
        return (from to to).also { lastUnits[categoryName] = it }
    }

    private fun rememberUnits() {
        val categories = converterCategories
        val name = categories[converterCategoryIndex.coerceIn(categories.indices)].name
        lastUnits[name] = converterFromToken to converterToToken
        persist(unitsFromKey(name), converterFromToken)
        persist(unitsToKey(name), converterToToken)
        refreshUnitsWidget()
    }

    private fun persist(key: String, value: String) {
        scope?.launch { dataStore.setString(key, value) }
    }

    /** The widget reads the same preferences, so it has to be told they moved. */
    private fun refreshUnitsWidget() {
        application.updateWidget(UnitsGlanceWidget::class)
    }

    fun setFrom(token: String) {
        converterFromToken = token
        rememberUnits()
    }

    fun setTo(token: String) {
        converterToToken = token
        rememberUnits()
    }

    fun setConverterInput(text: String) { converterValueText = text }
    fun swapUnits() {
        val categories = converterCategories
        val swappedInput = convert(categories[converterCategoryIndex.coerceIn(categories.indices)])
        val from = converterFromToken
        converterFromToken = converterToToken
        converterToToken = from
        if (swappedInput.toDoubleOrNull() != null) converterValueText = swappedInput
        rememberUnits()
    }

    fun retryCurrency() = loadCurrencyRates()

    private fun loadCurrencyRates() {
        if (currencyLoading) return
        currencyLoading = true
        currencyError = null
        scope?.launch {
            runCatching { ratesFetcher() }
                .onSuccess { dto ->
                    val category = UnitRegistry.currencyCategory(dto.rates)
                    if (category == null) {
                        currencyError = "No exchange rates available"
                    } else {
                        val wasEmpty = currencyCategory?.units.isNullOrEmpty()
                        currencyCategory = category
                        // If the user is already on the Currency tab with nothing picked yet, fill
                        // in their remembered pair (or USD -> EUR) now that units exist.
                        if (converterCategoryIndex == currencyIndex && wasEmpty) {
                            applyRememberedUnits(category)
                        }
                    }
                }
                .onFailure { currencyError = "Couldn't load exchange rates" }
            currencyLoading = false
        }
    }

    /**
     * Reopens the converter on the tab and unit pair the user left it on. Uses the awaiting getters
     * because at construction the mirrored preference snapshot may not be hydrated yet.
     */
    private fun restoreConverterSelection() {
        scope?.launch {
            val name = dataStore.getStringAwait(KEY_UNITS_CATEGORY) ?: return@launch
            val from = dataStore.getStringAwait(unitsFromKey(name))
            val to = dataStore.getStringAwait(unitsToKey(name))
            if (from != null && to != null) lastUnits.putIfAbsent(name, from to to)

            val categories = converterCategories
            val index = categories.indexOfFirst { it.name == name }
            if (index < 0) return@launch
            converterCategoryIndex = index
            // Currency has no units until rates arrive; loadCurrencyRates applies them then.
            if (categories[index].units.isNotEmpty()) applyRememberedUnits(categories[index])
        }
    }

    companion object {
        /** The Currency tab's stand-in before rates load: present so the tab shows, but empty. */
        private val EMPTY_CURRENCY_CATEGORY =
            UnitCategory("Currency", emptyList(), inEquations = false)
    }
}

/** Adapts [UnitConverterTab] to the [UnitConverterActions] interface for ViewModel delegation. */
internal fun converterActionAdapter(tab: UnitConverterTab): UnitConverterActions =
    object : UnitConverterActions {
        override fun selectCategory(index: Int) = tab.selectCategory(index)
        override fun setFrom(token: String) = tab.setFrom(token)
        override fun setTo(token: String) = tab.setTo(token)
        override fun setConverterInput(text: String) = tab.setConverterInput(text)
        override fun swapUnits() = tab.swapUnits()
        override fun retryCurrency() = tab.retryCurrency()
    }
