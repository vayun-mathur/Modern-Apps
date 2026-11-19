package com.vayunmathur.calendar.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.action.toParametersKey
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDefaults.defaultTextStyle
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.MainActivity
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.ui.atEndOfDayIn
import com.vayunmathur.calendar.ui.dateRangeString
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.widgets.MaWidget
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import androidx.glance.LocalContext
import kotlin.time.Clock

/** Positioned events for the agenda widget, keyed by day. */
data class CalendarWidgetData(
    val positionedEvents: Map<LocalDate, List<Instance>>,
)

/** Agenda Glance widget showing the next month of visible events grouped by day. */
class CalendarGlanceWidget : MaWidget<CalendarWidgetData>() {
    override suspend fun loadData(context: Context, id: GlanceId): CalendarWidgetData {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val today = now.date

        val nextMonth = today + DatePeriod(months = 1)
        val days = today..<nextMonth

        val zone = TimeZone.currentSystemDefault()
        val instances = Instance.getVisibleInstances(
            context,
            today.atStartOfDayIn(zone),
            nextMonth.atEndOfDayIn(zone),
        )
        val (allDay, notAllDay) = instances.partition { it.allDay }

        // Grouped straight off spanDays: the widget list needs no day-view columns,
        // and routing through computePositionedEventsForDay dropped zero-duration
        // (start == end) events, whose slice has no width.
        val positionedEvents = days.associateWith { day ->
            (notAllDay.filter { day in it.spanDays }.sortedBy { it.startDateTime }
                + allDay.filter { day in it.spanDays })
        }
        return CalendarWidgetData(positionedEvents)
    }

    override fun previewData(context: Context): CalendarWidgetData = CalendarWidgetData(emptyMap())

    @Composable
    override fun Content(data: CalendarWidgetData) {
        Content(LocalContext.current, data.positionedEvents)
    }

    @Composable
    override fun PreviewContent() {
        CalendarPreviewContent()
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun CalendarPreviewContent() {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val context = LocalContext.current

    Scaffold(
        titleBar = {
            TitleBar(ImageProvider(R.drawable.calendar_today_24px), DateString.dateWeekdayNoYear(today))
        },
        horizontalPadding = 0.dp
    ) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)) {
            DayHeader(dayHeaderLabel(today, today, context))
            CalendarEventRow(
                0xFF4285F4.toInt(),
                context.getString(R.string.widget_preview_event_1_title),
                context.getString(R.string.widget_preview_event_1_time),
            )
            CalendarEventRow(
                0xFF34A853.toInt(),
                context.getString(R.string.widget_preview_event_2_title),
                context.getString(R.string.widget_preview_event_2_time),
            )
            CalendarEventRow(
                0xFFEA4335.toInt(),
                context.getString(R.string.widget_preview_event_3_title),
                context.getString(R.string.widget_preview_event_3_time),
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun Content(context: Context, positionedEvents: Map<LocalDate, List<Instance>>) {

    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = now.date

    val nextMonth = today + DatePeriod(months = 1)
    val days = today..<nextMonth

    Scaffold(
        titleBar = {
            TitleBar(
                ImageProvider(R.drawable.calendar_today_24px),
                DateString.dateWeekdayNoYear(today),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        },
        horizontalPadding = 0.dp
    ) {
        LazyColumn(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)) {
            for(day in days) {
                // The map was keyed by the days loadData saw; rendering can
                // run after midnight (or with any stale/partial data), so a
                // missing day is an empty day, not a crash in the widget.
                val dayEvents = positionedEvents[day].orEmpty()
                if(dayEvents.isNotEmpty()) {
                    item {
                        DayHeader(dayHeaderLabel(day, today, context))
                    }
                }
                items(dayEvents) { instance ->
                    CalendarEventRow(
                        color = instance.color,
                        title = instance.eventTitle,
                        subtitle = dateRangeString(
                            context,
                            instance.startDateTime.date,
                            instance.endDateTime.date,
                            instance.startDateTime.time,
                            instance.endDateTime.time,
                            instance.allDay,
                            includeDate = false
                        ),
                        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>(actionParametersOf(
                            stringPreferencesKey("instance").toParametersKey() to Json.encodeToString(instance))))
                    )
                }
            }
        }
    }
}

/** "Today"/"Tomorrow" for those days, otherwise the weekday + date. */
private fun dayHeaderLabel(day: LocalDate, today: LocalDate, context: Context): String = when (day) {
    today -> context.getString(UiR.string.today)
    today + DatePeriod(days = 1) -> context.getString(R.string.tomorrow)
    else -> DateString.dateWeekday(day)
}

/** A day-group heading in the calendar widget list. */
@SuppressLint("RestrictedApi")
@Composable
private fun DayHeader(text: String) {
    Text(
        text,
        modifier = GlanceModifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
        style = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = GlanceTheme.colors.onSurface
        )
    )
}

/**
 * A single event row styled to match the email widget: a flush [GlanceTheme.colors.surface]
 * row with a leading color bar and a title over a subtitle.
 */
@SuppressLint("RestrictedApi")
@Composable
private fun CalendarEventRow(
    color: Int,
    title: String,
    subtitle: String,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 1.dp)
            .background(GlanceTheme.colors.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(GlanceModifier.width(6.dp).fillMaxHeight().background(ColorProvider(Color(color)))) {}
        Column(GlanceModifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title,
                style = TextStyle(
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1
            )
            Text(
                subtitle,
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1
            )
        }
    }
}


