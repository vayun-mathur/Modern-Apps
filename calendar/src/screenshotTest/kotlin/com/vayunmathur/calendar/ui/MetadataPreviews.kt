package com.vayunmathur.calendar.ui

import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.platform.CalendarActions
import com.vayunmathur.calendar.platform.CalendarUiState
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.platform.EventActions
import com.vayunmathur.calendar.platform.EventUiState
import com.vayunmathur.calendar.platform.SettingsActions
import com.vayunmathur.calendar.platform.SettingsUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-shaped: exercises the detail pane at a width where list and detail sit side by side. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/** A fixed Monday in March 2026. Never "today" — the images must not change per run. */
private val TODAY = LocalDate(2026, 3, 9)

private val ZONE = TimeZone.currentSystemDefault()

/**
 * Epoch millis for a wall-clock hour on [date]. Built in the rendering machine's zone, so
 * the *rendered* time ("9:00 AM") is the same everywhere even though the millis are not —
 * the screens convert back through the same zone.
 */
private fun at(date: LocalDate, hour: Int): Long =
    LocalDateTime(date, LocalTime(hour, 0)).toInstant(ZONE).toEpochMilliseconds()

private val PERSONAL = Calendar(
    id = 1,
    accountName = "Personal",
    displayName = "Personal",
    color = 0xFF4285F4.toInt(),
    accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
    visible = true,
)

private val WORK = Calendar(
    id = 2,
    accountName = "Personal",
    displayName = "Work",
    color = 0xFFEA4335.toInt(),
    accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
    visible = true,
)

/** A subscribed calendar: read-only, so settings offers no rename/recolour for it. */
private val HOLIDAYS = Calendar(
    id = 3,
    accountName = "Holidays",
    displayName = "United States holidays",
    color = 0xFF0B8043.toInt(),
    accessLevel = CalendarContract.Calendars.CAL_ACCESS_READ,
    visible = true,
)

private fun event(
    id: Long,
    calendar: Calendar,
    title: String,
    date: LocalDate,
    startHour: Int,
    endHour: Int,
    location: String = "",
    description: String = "",
) = Event(
    id = id,
    calendarID = calendar.id,
    title = title,
    description = description,
    location = location,
    color = null,
    start = at(date, startHour),
    end = at(date, endHour),
    timezone = ZONE.id,
    allDay = false,
    rrule = null,
)

/** The single occurrence of a non-recurring [event]. */
private fun instance(event: Event) = Instance(
    id = 100 + event.id!!,
    eventID = event.id!!,
    begin = event.start,
    end = event.end,
    timezone = event.timezone,
    allDay = false,
    eventTitle = event.title,
    color = 0,
    rrule = null,
)

private val TEAM_STANDUP = event(
    1, WORK, "Team standup", TODAY, 9, 10,
    location = "Meeting Room B",
    description = "Sprint sync, then a demo of the new import flow.",
)
private val SAMPLE_EVENTS = listOf(
    TEAM_STANDUP,
    event(2, PERSONAL, "Lunch with Alex", TODAY, 12, 13, location = "Cafe Rio"),
    event(3, PERSONAL, "Dentist appointment", LocalDate(2026, 3, 10), 15, 16, location = "Downtown Dental"),
    event(4, PERSONAL, "Yoga class", LocalDate(2026, 3, 11), 18, 19, location = "Studio 5"),
    event(5, WORK, "Project deadline", LocalDate(2026, 3, 12), 17, 18),
    event(6, PERSONAL, "Weekend hike", LocalDate(2026, 3, 14), 8, 12, location = "Trailhead"),
)

/**
 * Store listing images for `:calendar`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :calendar:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/calendar/`, where `release.sh` picks them up.
 *
 * Things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Month`/`Preview2Event`/... sort into listing
 *    order. Renumber the functions if you reorder the listing.
 *  - Everything must be a literal. Both screens below normally read the system
 *    CalendarProvider; here the whole input is the state above, which is also what makes
 *    the output reproducible from a clean checkout.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class. Top-level previews land in a synthetic
 *    `…Kt` facade that the screenshot engine silently skips.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching
 * the `cmd uimode night yes` the old on-device generator used. Material You sources its
 * palette from the wallpaper, which does not exist here, so these render with the fallback
 * scheme rather than a user's accent colour.
 *
 * Coverage: `Calendar` (month), `Event` (detail) and `Settings` on phone, plus both the
 * month (main) and event detail on large-tablet.
 * Exceptions (no stateless slice; ViewModel + system CalendarProvider / intent URIs):
 * `EditEvent`, `HolidayCalendars` and `ImportIcs` are not preview-rendered — faking them
 * from literals would not show the real editor/import screens. Dialog routes
 * (`GotoDialog`, pickers, recurrence, settings dialogs) are dialogs, not full screens.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-month", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Month() {
        DynamicTheme(darkTheme = true) {
            CalendarScreen(
                state = CalendarUiState(
                    layout = CalendarViewModel.CalendarLayout.Month,
                    dateViewing = TODAY,
                    today = TODAY,
                    events = SAMPLE_EVENTS,
                    calendars = mapOf(PERSONAL.id to PERSONAL, WORK.id to WORK),
                    calendarVisibility = mapOf(PERSONAL.id to true, WORK.id to true),
                    previewInstances = SAMPLE_EVENTS.map { instance(it) },
                ),
                actions = CalendarActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-event", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Event() {
        DynamicTheme(darkTheme = true) {
            EventScreen(
                state = EventUiState(
                    event = TEAM_STANDUP,
                    calendar = WORK,
                    instance = instance(TEAM_STANDUP),
                ),
                actions = EventActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-settings", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Settings() {
        DynamicTheme(darkTheme = true) {
            SettingsScreen(
                state = SettingsUiState(
                    calendars = listOf(PERSONAL, WORK, HOLIDAYS),
                    calendarVisibility = mapOf(PERSONAL.id to true, WORK.id to true, HOLIDAYS.id to false),
                    layout = CalendarViewModel.CalendarLayout.Month,
                    themeMode = CalendarViewModel.ThemeMode.Dark,
                ),
                actions = SettingsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-month-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5MonthExpanded() {
        DynamicTheme(darkTheme = true) {
            CalendarScreen(
                state = CalendarUiState(
                    layout = CalendarViewModel.CalendarLayout.Month,
                    dateViewing = TODAY,
                    today = TODAY,
                    events = SAMPLE_EVENTS,
                    calendars = mapOf(PERSONAL.id to PERSONAL, WORK.id to WORK),
                    calendarVisibility = mapOf(PERSONAL.id to true, WORK.id to true),
                    previewInstances = SAMPLE_EVENTS.map { instance(it) },
                ),
                actions = CalendarActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4Expanded() {
        // The detail pane at desktop width: the event the month grid selection opens.
        DynamicTheme(darkTheme = true) {
            EventScreen(
                state = EventUiState(
                    event = TEAM_STANDUP,
                    calendar = WORK,
                    instance = instance(TEAM_STANDUP),
                ),
                actions = EventActions.Noop,
            )
        }
    }
}
