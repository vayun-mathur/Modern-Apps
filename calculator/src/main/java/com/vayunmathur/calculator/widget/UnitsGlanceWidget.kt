package com.vayunmathur.calculator.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vayunmathur.calculator.MainActivity
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.platform.KEY_UNITS_CATEGORY
import com.vayunmathur.calculator.domain.UnitDef
import com.vayunmathur.calculator.domain.UnitRegistry
import com.vayunmathur.calculator.domain.formatResult
import com.vayunmathur.calculator.platform.unitsFromKey
import com.vayunmathur.calculator.platform.unitsToKey
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.widgets.MaWidget
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.vayunmathur.library.widgets.MaWidgetReceiver

/** What the widget draws: the category it is on and the conversion for that category's unit pair. */
data class UnitsWidgetState(val categoryName: String, val conversion: String)

/** What the widget draws: the converter state plus whether the category list is showing. */
data class UnitsWidgetData(val state: UnitsWidgetState, val expanded: Boolean)

/**
 * Home-screen converter for the units tab. Shows the unit pair the user last picked in the app
 * and lets them switch category without opening it.
 *
 * The category picker is drawn inline and toggled by [ToggleUnitsCategoryListAction]: Glance has
 * no popup or menu primitive, so an expand/collapse list inside the widget is what a dropdown has
 * to be here. The selected category lives in the same preference the app reads on launch, so
 * picking one from either side keeps both in step.
 */
class UnitsGlanceWidget : MaWidget<UnitsWidgetData>() {

    // Broad catches are deliberate: a widget update runs unattended in a cold process where the
    // store or Glance state may be unreadable, and every failure mode below degrades to fallback
    // UI (logged) rather than taking down the widget host.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadData(context: Context, id: GlanceId): UnitsWidgetData {
        val state = try {
            loadState(context)
        } catch (t: Throwable) {
            Log.error(TAG, "Failed to read converter selection", t)
            defaultState()
        }
        val expanded = try {
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[EXPANDED] == true
        } catch (t: Throwable) {
            Log.error(TAG, "Failed to read widget state", t)
            false
        }
        return UnitsWidgetData(state, expanded)
    }

    override fun previewData(context: Context): UnitsWidgetData =
        UnitsWidgetData(SAMPLE, expanded = false)

    @Composable
    override fun Content(data: UnitsWidgetData) {
        UnitsWidgetContent(data.state, data.expanded)
    }

    @SuppressLint("RestrictedApi")
    @Composable
    private fun UnitsWidgetContent(state: UnitsWidgetState, expanded: Boolean) {
        val context = LocalContext.current
        Scaffold(
            titleBar = {
                TitleBar(
                    startIcon = ImageProvider(R.drawable.straighten_24px),
                    title = context.getString(R.string.units),
                )
            },
            horizontalPadding = 12.dp,
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                CategoryLabel(state.categoryName, expanded)
                // Claimed from the column rather than fillMaxSize, which as a weighted sibling
                // would squeeze the label above out of the layout.
                val rest = GlanceModifier.fillMaxWidth().defaultWeight()
                if (expanded) {
                    CategoryList(state.categoryName, rest)
                } else {
                    Conversion(state.conversion, rest)
                }
            }
        }
    }

    /** The collapsed dropdown: the current category plus the caret that opens the list. */
    @Composable
    private fun CategoryLabel(categoryName: String, expanded: Boolean) {
        val context = LocalContext.current
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(16.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(actionRunCallback<ToggleUnitsCategoryListAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = categoryName,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSecondaryContainer,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Image(
                provider = ImageProvider(
                    if (expanded) R.drawable.arrow_drop_up_24px else R.drawable.arrow_drop_down_24px,
                ),
                contentDescription = context.getString(R.string.units_widget_choose_category),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(20.dp),
            )
        }
    }

    @Composable
    private fun Conversion(conversion: String, modifier: GlanceModifier) {
        Box(
            modifier = modifier
                .padding(top = 8.dp)
                .clickable(actionStartActivity(openUnitsIntent(LocalContext.current))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = conversion,
                style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.onSurface),
                maxLines = 2,
            )
        }
    }

    /**
     * The expanded dropdown. [UnitRegistry.categories] holds no Currency entry — the app appends
     * a live one once rates load — so iterating it leaves currencies out, which is what we want
     * in a widget that cannot fetch them.
     */
    @Composable
    private fun CategoryList(selectedName: String, modifier: GlanceModifier) {
        LazyColumn(modifier = modifier.padding(top = 4.dp)) {
            items(UnitRegistry.categories) { category ->
                CategoryRow(category.name, selected = category.name == selectedName)
            }
        }
    }

    /**
     * A row in the expanded list. Selection is shown with weight and colour rather than a
     * checkmark, so the list reads the same as the converter's own category dropdown; the
     * collapsed label above stays visible while the list is open either way.
     */
    @Composable
    private fun CategoryRow(name: String, selected: Boolean) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .clickable(
                    actionRunCallback<SelectUnitsCategoryAction>(
                        actionParametersOf(SelectUnitsCategoryAction.CATEGORY_NAME to name),
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }

    /** Intent extras, Glance state keys and preview sample for [UnitsGlanceWidget]. */
    companion object {
        private const val TAG = "UnitsGlanceWidget"

        /** Extra that tells [MainActivity] to open on the units tab rather than the calculator. */
        const val EXTRA_OPEN_UNITS = "openUnits"

        /** Whether the inline category list is showing. Per-widget, so it is Glance state. */
        val EXPANDED = booleanPreferencesKey("units_widget_category_expanded")

        /** Static, so a preview never depends on stored state or a fetch that could fail. */
        private val SAMPLE = UnitsWidgetState("Temperature", "100\u202F°C = 212\u202F°F")

        /**
         * SINGLE_TOP so a tap on an already-running app delivers the intent to `onNewIntent`
         * instead of being swallowed: NEW_TASK alone finds the existing task, brings it forward
         * and drops the intent, which is what makes a second tap land on the wrong tab. Not
         * CLEAR_TOP — that recreates the activity and takes the view model, and with it the
         * user's expression, history and graphs, down with it.
         */
        private fun openUnitsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_UNITS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Mirrors the app's `restoreConverterSelection`: the persisted category, then the pair last
         * used in it, falling back to that category's first two units. Uses the awaiting getters
         * because a widget update can run in a cold process where the store is not hydrated yet.
         */
        private suspend fun loadState(context: Context): UnitsWidgetState {
            val store = DataStoreUtils.getInstance(context)
            val name = store.getStringAwait(KEY_UNITS_CATEGORY)
            val category = UnitRegistry.categories.firstOrNull { it.name == name }
                ?: UnitRegistry.categories[0]
            val units = category.units
            val fromToken = store.getStringAwait(unitsFromKey(category.name))
            val toToken = store.getStringAwait(unitsToKey(category.name))
            val from = units.firstOrNull { it.token == fromToken } ?: units[0]
            val to = units.firstOrNull { it.token == toToken } ?: units.getOrElse(1) { units[0] }
            return UnitsWidgetState(category.name, conversionText(from, to))
        }

        /** What the widget shows before the user has picked anything, or if the store is unreadable. */
        private fun defaultState(): UnitsWidgetState {
            val category = UnitRegistry.categories[0]
            val units = category.units
            return UnitsWidgetState(
                category.name,
                conversionText(units[0], units.getOrElse(1) { units[0] }),
            )
        }

        /** The converter's value box is not persisted, so the widget states the unit rate. */
        private fun conversionText(from: UnitDef, to: UnitDef): String {
            val value = 1.0
            val converted = formatResult(to.fromBase(from.toBase(value)))
            return "${formatResult(value)}\u202F${from.symbol} = $converted\u202F${to.symbol}"
        }
    }
}

/**
 * Opens and closes the inline category list. Glance state survives widget updates, so the list
 * stays open across the recomposition this triggers.
 */
class ToggleUnitsCategoryListAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[UnitsGlanceWidget.EXPANDED] = prefs[UnitsGlanceWidget.EXPANDED] != true
        }
        UnitsGlanceWidget().update(context, glanceId)
    }
}

/** Switches the widget to a category and collapses the list again. */
class SelectUnitsCategoryAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val name = parameters[CATEGORY_NAME] ?: return
        // The preference the app restores on launch, so the converter reopens on this category too.
        DataStoreUtils.getInstance(context)
            .setString(KEY_UNITS_CATEGORY, name)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[UnitsGlanceWidget.EXPANDED] = false
        }
        UnitsGlanceWidget().update(context, glanceId)
    }

    /** Action parameter keys for the units widget actions. */
    companion object {
        val CATEGORY_NAME = ActionParameters.Key<String>("categoryName")
    }
}

/**
 * No periodic update is scheduled: a conversion only changes when the user changes it, and both
 * sides push an update when they do — the converter through `CalculatorViewModel`, the widget
 * through its own action callbacks.
 */
class UnitsGlanceWidgetReceiver : MaWidgetReceiver<UnitsGlanceWidget>(
    widgetClass = UnitsGlanceWidget::class,
    receiverClass = UnitsGlanceWidgetReceiver::class,
    hourly = false,
    widgetFactory = ::UnitsGlanceWidget,
)
