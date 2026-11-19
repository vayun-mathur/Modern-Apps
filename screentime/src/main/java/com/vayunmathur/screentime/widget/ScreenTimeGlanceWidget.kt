package com.vayunmathur.screentime.widget
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.vayunmathur.screentime.platform.UsageAccess
import com.vayunmathur.screentime.platform.AppTimers
import com.vayunmathur.library.widgets.MaWidgetReceiver
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.GlanceAppWidgetManager

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.vayunmathur.library.widgets.MaWidget
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.MainActivity

internal val TotalMinutesKey = longPreferencesKey("screentime_total_minutes")
internal val TopLabelKey = stringPreferencesKey("screentime_top_label")
internal val TopMinutesKey = longPreferencesKey("screentime_top_minutes")

/**
 * Today's screen time at a glance.
 *
 * Shows the day's total plus the top app, refreshed whenever the coordinator reconciles
 * (timer spent, window boundary) and on the standard widget update path. Tapping opens the
 * dashboard. State is written by the app process into Glance preferences; the widget itself
 * never queries UsageStats (no cross-process permission story to maintain).
 */
class ScreenTimeGlanceWidget : MaWidget<Unit>() {
    override suspend fun loadData(context: Context, id: GlanceId): Unit = Unit

    override fun previewData(context: Context): Unit = Unit

    @Composable
    override fun Content(data: Unit) {
        val context = LocalContext.current
        val prefs = currentState<Preferences>()
        val total = prefs[TotalMinutesKey] ?: 0L
        val topLabel = prefs[TopLabelKey]
        val topMinutes = prefs[TopMinutesKey] ?: 0L
        ScreenTimeWidgetContent(
            context = context,
            total = total,
            topLabel = topLabel,
            topMinutes = topMinutes,
        )
    }

    @Composable
    override fun PreviewContent() {
        ScreenTimeWidgetContent(
            context = LocalContext.current,
            total = 187,
            topLabel = "Example",
            topMinutes = 64,
        )
    }
}

@Composable
private fun ScreenTimeWidgetContent(
    context: Context,
    total: Long,
    topLabel: String?,
    topMinutes: Long,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(20.dp)
            .padding(16.dp)
            .clickable(
                actionStartActivity(
                    Intent(LocalContext.current, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = context.getString(R.string.range_today),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            Text(
                text = formatWidgetMinutes(total),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            if (topLabel != null) {
                Text(
                    text = "$topLabel · ${formatWidgetMinutes(topMinutes)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

private fun formatWidgetMinutes(minutes: Long): String =
    if (minutes < MINUTES_PER_HOUR) {
        "${minutes}m"
    } else {
        "${minutes / MINUTES_PER_HOUR}h ${minutes % MINUTES_PER_HOUR}m".trim()
    }

private const val MINUTES_PER_HOUR = 60L

// No periodic refresh: content changes only when usage accrues, and the coordinator
// pushes an update on every reconcile that matters (see WidgetRefresh).
/** Glance widget receiver hosting the screen-time widget. */
class ScreenTimeGlanceWidgetReceiver : MaWidgetReceiver<ScreenTimeGlanceWidget>(
    widgetClass = ScreenTimeGlanceWidget::class,
    receiverClass = ScreenTimeGlanceWidgetReceiver::class,
    hourly = false,
    widgetFactory = ::ScreenTimeGlanceWidget,
)

/**
 * Pushes fresh totals into the widget.
 *
 * Called from the coordinator after reconcile so the widget tracks timers, pauses and
 * wind-down without polling UsageStats on its own schedule.
 */
object WidgetRefresh {

    fun refresh(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            UsageAccess.ensure(app)
            val timers = AppTimers(app)
            val byPackage = timers.usageTodayByPackage() ?: emptyMap()
            val total = byPackage.values.sum() / 60_000L
            val top = byPackage.maxByOrNull { it.value }
            val topLabel = top?.key?.let { labelFor(app, it) }
            val topMinutes = (top?.value ?: 0L) / 60_000L
            runCatching {
                val manager = GlanceAppWidgetManager(app)
                val widget = ScreenTimeGlanceWidget()
                val ids = manager.getGlanceIds(ScreenTimeGlanceWidget::class.java)
                for (id in ids) {
                    updateAppWidgetState(app, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[TotalMinutesKey] = total
                            if (topLabel != null) {
                                this[TopLabelKey] = topLabel
                                this[TopMinutesKey] = topMinutes
                            }
                        }
                    }
                    widget.update(app, id)
                }
            }.onFailure {
                // The widget host may be absent (headless MAOS builds); usage tracking must
                // never depend on a home-screen widget rendering.
            }
        }
    }

    private fun labelFor(context: Context, packageName: String): String =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
}
