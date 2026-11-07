package com.vayunmathur.calendar.glance

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.testing.unit.assertHasText
import androidx.glance.testing.unit.hasText
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
class GlanceContentTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `agenda widget content shows headers and event rows`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(400.dp, 300.dp))
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(zone).date
            val nextMonth = today + DatePeriod(months = 1)
            val standup = D.instance(
                id = 42, eventID = 10, date = today, startHour = 9, endHour = 10,
                title = "GlanceStandup",
            )
            val allDay = D.instance(
                id = 43, eventID = 10, date = today, title = "GlanceAllDay", allDay = true,
            )
            val days = generateSequence(today) { it + DatePeriod(days = 1) }
                .takeWhile { it < nextMonth }.toList()
            val positioned: Map<LocalDate, List<Instance>> = days.associateWith { day ->
                if (day == today) listOf(standup, allDay) else emptyList()
            }
            provideComposable {
                Content(context, positioned)
            }
            awaitIdle()
            onNode(hasText("GlanceStandup")).assertExists()
            onNode(hasText("GlanceStandup")).assertHasText("GlanceStandup")
            onNode(hasText("GlanceAllDay")).assertExists()
        }
    }

    @Test
    fun `agenda widget content with no events renders title bar only`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(400.dp, 300.dp))
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(zone).date
            val nextMonth = today + DatePeriod(months = 1)
            val days = generateSequence(today) { it + DatePeriod(days = 1) }
                .takeWhile { it < nextMonth }.toList()
            provideComposable {
                Content(context, days.associateWith { emptyList<Instance>() })
            }
            awaitIdle()
            onNode(hasText("GlanceStandup")).assertDoesNotExist()
        }
    }

    @Test
    fun `month widget content shows month label chips and more count`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(400.dp, 300.dp))
            val weeks = computeMonthGrid(2026, Month.MARCH, firstDayOfWeek = 1)
            val busyDay = LocalDate(2026, 3, 10)
            val busy = (1..5).map { i ->
                D.instance(id = 100L + i, eventID = 10, date = busyDay, startHour = 8 + i, endHour = 9 + i, title = "ChipEvent$i")
            }
            val eventsByDay: Map<LocalDate, List<Instance>> = mapOf(
                busyDay to busy,
                LocalDate(2026, 3, 9) to listOf(D.instance(id = 90, eventID = 10, date = LocalDate(2026, 3, 9), title = "MonthStandup")),
            )
            provideComposable {
                MonthContent(
                    monthLabel = "Mar 2026",
                    weeks = weeks,
                    eventsByDay = eventsByDay,
                    today = D.TODAY,
                    viewingMonth = Month.MARCH,
                    weekdayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
                )
            }
            awaitIdle()
            onNode(hasText("Mar 2026")).assertExists()
            onNode(hasText("MonthStandup")).assertExists()
            onNode(hasText("ChipEvent1")).assertExists()
            onNode(androidx.glance.testing.unit.hasTextEqualTo("Mon")).assertExists()
        }
    }

    @Test
    fun `month widget content for other month with no events renders grid`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(360.dp, 280.dp))
            val weeks = computeMonthGrid(2026, Month.APRIL, firstDayOfWeek = 7)
            provideComposable {
                MonthContent(
                    monthLabel = "Apr 2026",
                    weeks = weeks,
                    eventsByDay = emptyMap(),
                    today = D.TODAY,
                    viewingMonth = Month.APRIL,
                    weekdayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
                )
            }
            awaitIdle()
            onNode(hasText("Apr 2026")).assertExists()
            onNode(hasText("MonthStandup")).assertDoesNotExist()
            assertTrue(weeks.flatten().contains(LocalDate(2026, 4, 1)))
        }
    }

}
