package com.vayunmathur.calculator.widget

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.vayunmathur.calculator.util.Expression
import com.vayunmathur.calculator.util.ExpressionError
import com.vayunmathur.calculator.util.UnitRegistry
import com.vayunmathur.calculator.util.formatQuantity

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
 * Evaluates [input] with [com.vayunmathur.calculator.util.Expression], the same parser the
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

    companion object {
        /**
         * Best-effort key tick. Never throws: every branch is guarded because this runs
         * before the widget state write, and a haptics failure must not swallow the tap.
         */
        @Suppress("TooGenericExceptionCaught", "Deprecation")
        private fun emitKeyTick(context: Context) {
            try {
                val vibrator = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Vibrator::class.java)
                    }
                }.getOrNull() ?: return
                if (!vibrator.hasVibrator()) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vibrator.vibrate(10L)
                }
            } catch (_: Throwable) {
                // Haptics are cosmetic; the tap has already been queued for handling.
            }
        }
    }
}
