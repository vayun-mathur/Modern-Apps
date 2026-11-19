package com.vayunmathur.calculator.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vayunmathur.calculator.MainActivity
import com.vayunmathur.calculator.domain.renderInputForDisplay
import com.vayunmathur.library.widgets.MaWidget
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.vayunmathur.calculator.domain.Expression
import com.vayunmathur.calculator.domain.ExpressionError
import com.vayunmathur.calculator.domain.UnitRegistry
import com.vayunmathur.calculator.domain.formatQuantity
import com.vayunmathur.library.widgets.MaWidgetReceiver

/** How a key is coloured: digits recede, operators sit forward, `=` is the primary action. */
private enum class KeyEmphasis { Digit, Operator, Primary }

private class WidgetKey(
    val label: String,
    val command: String,
    val emphasis: KeyEmphasis = KeyEmphasis.Digit,
)

/** Appends [text] verbatim, labelled with [label] (which may be a prettier glyph). */
private fun insert(label: String, text: String = label, emphasis: KeyEmphasis = KeyEmphasis.Digit) =
    WidgetKey(label, COMMAND_APPEND_PREFIX + text, emphasis)

private val KEYPAD: List<List<WidgetKey>> = listOf(
    listOf(
        WidgetKey("AC", COMMAND_CLEAR, KeyEmphasis.Operator),
        insert("(", emphasis = KeyEmphasis.Operator),
        insert(")", emphasis = KeyEmphasis.Operator),
        insert("÷", "/", KeyEmphasis.Operator),
    ),
    listOf(insert("7"), insert("8"), insert("9"), insert("×", "*", KeyEmphasis.Operator)),
    listOf(insert("4"), insert("5"), insert("6"), insert("−", "-", KeyEmphasis.Operator)),
    listOf(insert("1"), insert("2"), insert("3"), insert("+", emphasis = KeyEmphasis.Operator)),
    listOf(
        insert("0"),
        insert("."),
        WidgetKey("⌫", COMMAND_BACKSPACE, KeyEmphasis.Operator),
        WidgetKey("=", COMMAND_EVALUATE, KeyEmphasis.Primary),
    ),
)

/**
 * A home-screen keypad. Unlike the app's screen there is no ViewModel to hold the expression,
 * so the in-progress input lives in the widget's own Glance preferences and every tap goes
 * through [CalculatorKeyAction], which rewrites that state and asks for a re-render.
 */
class CalculatorGlanceWidget : MaWidget<Unit>() {
    override suspend fun loadData(context: Context, id: GlanceId): Unit = Unit

    override fun previewData(context: Context): Unit = Unit

    @Composable
    override fun Content(data: Unit) {
        val prefs = currentState<Preferences>()
        val input = prefs[CalculatorInputKey].orEmpty()
        val result = evaluateForWidget(input, prefs[CalculatorAnswerKey] ?: 0.0)
        CalculatorWidgetContent(
            input = input,
            result = result?.display.orEmpty(),
            transparent = prefs[CalculatorTransparentKey] == true,
        )
    }

    @Composable
    override fun PreviewContent() {
        // Opaque: the picker draws previews on its own background, and a
        // transparent one there reads as a broken widget.
        CalculatorWidgetContent("12*8", "96", transparent = false)
    }
}

@Composable
private fun CalculatorWidgetContent(input: String, result: String, transparent: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .then(
                if (transparent) GlanceModifier
                else GlanceModifier.background(GlanceTheme.colors.surface)
            )
            .cornerRadius(20.dp)
            .padding(6.dp),
    ) {
        Display(input, result)
        KEYPAD.forEach { row ->
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEach { key -> KeypadKey(key) }
            }
        }
    }
}

/** The expression and its running result. Tapping through opens the full calculator. */
@Composable
private fun Display(input: String, result: String) {
    val displayInput = renderInputForDisplay(input).ifEmpty { "0" }
        .replace("*", "×").replace("/", "÷").replace("-", "−")
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(
                actionStartActivity(
                    Intent(LocalContext.current, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = displayInput,
            modifier = GlanceModifier.fillMaxWidth(),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            ),
            maxLines = 1,
        )
        Text(
            text = result,
            modifier = GlanceModifier.fillMaxWidth(),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.KeypadKey(key: WidgetKey) {
    val background: ColorProvider = when (key.emphasis) {
        KeyEmphasis.Digit -> GlanceTheme.colors.surfaceVariant
        KeyEmphasis.Operator -> GlanceTheme.colors.secondaryContainer
        KeyEmphasis.Primary -> GlanceTheme.colors.primary
    }
    val foreground: ColorProvider = when (key.emphasis) {
        KeyEmphasis.Digit -> GlanceTheme.colors.onSurfaceVariant
        KeyEmphasis.Operator -> GlanceTheme.colors.onSecondaryContainer
        KeyEmphasis.Primary -> GlanceTheme.colors.onPrimary
    }
    // Outer box carries the gap between keys; the inner one is the tappable, rounded surface.
    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp)) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(background)
                .cornerRadius(14.dp)
                .clickable(
                    actionRunCallback<CalculatorKeyAction>(
                        actionParametersOf(CalculatorKeyParam to key.command),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = key.label,
                style = TextStyle(color = foreground, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

/** The in-progress expression, in the same re-parsable form the app's input line uses. */
internal val CalculatorInputKey = stringPreferencesKey("calculator_widget_input")

/** The last computed result, so `ans` resolves the same way it does in the app. */
internal val CalculatorAnswerKey = doublePreferencesKey("calculator_widget_answer")

/** Which key was tapped, as one of the `COMMAND_*` values below. */
internal val CalculatorKeyParam = ActionParameters.Key<String>("calculator_widget_key")

/**
 * Whether this instance drops its own surface and lets the wallpaper through. Per-widget
 * Glance state, so two copies of the widget can be styled differently.
 */
internal val CalculatorTransparentKey = booleanPreferencesKey("calculator_widget_transparent")

internal const val COMMAND_CLEAR = "clear"
internal const val COMMAND_BACKSPACE = "backspace"
internal const val COMMAND_EVALUATE = "evaluate"

/** Prefixed rather than bare so an appended "=" or "clear" could never be mistaken for a command. */
internal const val COMMAND_APPEND_PREFIX = "append:"

/**
 * Longest expression the widget keeps. Every tap appends to the stored input, which is then
 * re-read from DataStore, re-parsed for the live preview and shipped to the host as RemoteViews
 * text — so an unbounded string made each tap slower than the last until updates stalled (#641).
 * A few hundred chars is far beyond what the single-line display fits; anything longer only ever
 * came from unbounded growth. `take` (not `takeLast`) keeps the start of what was typed, and also
 * shrinks an already-oversized value left behind from before the cap on the next tap.
 */
internal const val MAX_WIDGET_INPUT_LENGTH = 200

/** Appends [text] to the widget [input], keeping the stored expression bounded. Pure for tests. */
internal fun appendWidgetInput(input: String, text: String): String =
    (input + text).take(MAX_WIDGET_INPUT_LENGTH)

/**
 * A result of the app's evaluator, in both the pretty form for the display and the ASCII
 * token form that re-parses so it can seed the next calculation.
 */
internal class WidgetResult(val display: String, val token: String, val value: Double)

/**
 * Evaluates [input] with [com.vayunmathur.calculator.domain.Expression], the same parser the
 * app uses. Returns null for a blank, unparsable or undefined expression, which the widget
 * renders as an empty result line.
 */
internal fun evaluateForWidget(input: String, ans: Double): WidgetResult? {
    if (input.isBlank()) return null
    val quantity = try {
        Expression.parse(input).evalQuantity(ans = ans)
    } catch (ignored: ExpressionError) {
        return null
    }
    // Non-finite results have no re-parsable token form; storing "∞" as
    // the widget input would strand it (the app's evaluate() does the same).
    if (!quantity.value.isFinite()) return null
    val unit = if (quantity.isDimensionless || quantity.instant) {
        null
    } else {
        UnitRegistry.defaultUnitFor(quantity.dimension)
    }
    return WidgetResult(
        display = formatQuantity(quantity, unit),
        token = formatQuantity(quantity, unit, useToken = true),
        value = quantity.value,
    )
}

/**
 * Handles every keypad tap. Glance dispatches this through the widget's own receiver, so the
 * only state that survives a tap is what gets written to the widget's preferences here.
 */
class CalculatorKeyAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val command = parameters[CalculatorKeyParam] ?: return
        // Glance has no LocalHapticFeedback, so tick directly — same EFFECT_TICK the
        // keyboard service uses. Fires before the state write so a slow update can't eat it.
        // Outside runCatching-style handling: an EFFECT_TICK tick is cosmetic, and the
        // vibrator service is known to throw (missing feature, dead binder, pre-Q effect)
        // on exactly the devices whose widgets must still work — a haptics failure must
        // never swallow the tap. Catch Throwable (not just Exception) and gate the
        // predefined effect to its Q+ API, with a legacy fallback beneath it.
        emitKeyTick(context)
        updateAppWidgetState(context, glanceId) { prefs ->
            // Clamp first: shrinks an already-oversized value left behind from before the
            // cap (#641) so every tap heals, not just appends.
            val rawInput = prefs[CalculatorInputKey].orEmpty()
            val input = rawInput.take(MAX_WIDGET_INPUT_LENGTH)
            if (input.length < rawInput.length) prefs[CalculatorInputKey] = input
            when {
                command == COMMAND_CLEAR -> prefs[CalculatorInputKey] = ""
                command == COMMAND_BACKSPACE -> prefs[CalculatorInputKey] = input.dropLast(1)
                command == COMMAND_EVALUATE -> {
                    val result = evaluateForWidget(input, prefs[CalculatorAnswerKey] ?: 0.0)
                    if (result != null) {
                        prefs[CalculatorInputKey] = result.token
                        prefs[CalculatorAnswerKey] = result.value
                    }
                }
                command.startsWith(COMMAND_APPEND_PREFIX) ->
                    prefs[CalculatorInputKey] = appendWidgetInput(
                        input,
                        command.removePrefix(COMMAND_APPEND_PREFIX),
                    )
            }
        }
        // Writing state alone does not reliably recompose; the explicit update does.
        CalculatorGlanceWidget().update(context, glanceId)
    }

    /** Haptics helper for widget key taps. */
    companion object {
        /**
         * Best-effort key tick. Never throws: every branch is guarded because this runs
         * before the widget state write, and a haptics failure must not swallow the tap.
         */
        @Suppress("TooGenericExceptionCaught", "Deprecation")
        private fun emitKeyTick(context: Context) {
            try {
                val vibrator = runCatching {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                }.getOrNull() ?: return
                if (!vibrator.hasVibrator()) return
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } catch (_: Throwable) {
                // Haptics are cosmetic; the tap has already been queued for handling.
            }
        }
    }
}

/** Glance receiver for the calculator home-screen widget, refreshed only on key taps. */
class CalculatorGlanceWidgetReceiver : MaWidgetReceiver<CalculatorGlanceWidget>(
    widgetClass = CalculatorGlanceWidget::class,
    receiverClass = CalculatorGlanceWidgetReceiver::class,
    // No periodic refresh: the keypad's content only changes when the user taps a key.
    hourly = false,
    widgetFactory = ::CalculatorGlanceWidget,
)
