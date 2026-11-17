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
