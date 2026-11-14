package com.vayunmathur.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.glance.CalendarGlanceWidgetReceiver
import com.vayunmathur.calendar.ui.CalendarScreen
import com.vayunmathur.calendar.ui.EditEventScreen
import com.vayunmathur.calendar.ui.EventScreen
import com.vayunmathur.calendar.ui.HolidayCalendarsScreen
import com.vayunmathur.calendar.ui.ImportIcsScreen
import com.vayunmathur.calendar.ui.SettingsScreen
import com.vayunmathur.calendar.ui.parseICSFile
import com.vayunmathur.calendar.ui.dialogs.CalendarPickerDialog
import com.vayunmathur.calendar.ui.dialogs.CalendarSetDateDialog
import com.vayunmathur.calendar.ui.dialogs.RecurrenceDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsAddCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsChangeColorDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsDefaultRemindersDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsDeleteCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsRenameCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.TimezonePickerDialog
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.calendar.util.ReminderScheduler
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Calendar entry activity: hosts the navigation graph, applies the pinned light/dark
 * choice, and routes intent extras into pending navigation state.
 */
class MainActivity : MaActivity() {
    private val importUris = mutableStateOf<List<String>>(emptyList())
    private val pendingRoute = mutableStateOf<Route?>(null)
    private val pendingReminderFallback = mutableStateOf<ReminderFallback?>(null)
    private val pendingDate = mutableStateOf<LocalDate?>(null)

    override fun onCreateBeforeContent() {
        updateWidgetPreviews(CalendarGlanceWidgetReceiver::class)
        handleIntent(intent)
    }

    /**
     * Calendar lets the user pin light/dark in settings; that choice wraps the
     * whole activity theme rather than just the screens.
     */
    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        val dataStore = remember { DataStoreUtils.getInstance(this) }
        val themeName by dataStore.stringFlow("theme_mode")
            .collectAsState(initial = dataStore.getString("theme_mode"))
        val darkTheme = when (
            themeName?.let {
                runCatching { CalendarViewModel.ThemeMode.valueOf(it) }.getOrNull()
            }
        ) {
            CalendarViewModel.ThemeMode.Light -> false
            CalendarViewModel.ThemeMode.Dark -> true
            else -> null
        }
        DynamicTheme(darkTheme, content = content)
    }

    @Composable
    override fun Content() {
        CalendarAppContent(
            activityIntent = intent,
            importUris = importUris,
            pendingRoute = pendingRoute,
            pendingReminderFallback = pendingReminderFallback,
            pendingDate = pendingDate,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // isTimeEpochIntent lives at file level so resolveDateJumpTarget can share it.

    private fun handleIntent(intent: Intent?) {
        val it = intent ?: return
        // Date-jump intents never carry files; pendingDate drives the
        // LaunchedEffect above for onNewIntent (onCreate is covered there too).
        if (consumeTimeJumpIntent(it)) return
        // Instance deep-link: never crash on malformed extras (exported activity).
        if (consumeInstanceLink(it)) return
        // ACTION_INSERT from other apps while already open (singleTask).
        if (consumeInsertIntent(it)) return
        consumeImportUris(it)
    }

    /**
     * Handles a time/date-jump VIEW intent by stashing the date in [pendingDate].
     * Returns true when the intent was a date jump (it never carries files).
     */
    private fun consumeTimeJumpIntent(it: Intent): Boolean {
        if (!isTimeEpochIntent(it)) return false
        it.data?.lastPathSegment?.toLongOrNull()?.let { timestamp ->
            runCatching {
                Instant.fromEpochMilliseconds(timestamp)
                    .toLocalDateTime(TimeZone.currentSystemDefault()).date
            }.getOrNull()?.let { date -> pendingDate.value = date }
        }
        return true
    }

    /**
     * Handles an instance deep-link extra. Returns true when the intent carried no
     * stream data, so it must not fall into ICS handling.
     *
     * Notification taps arrive with the full Instance JSON when the provider
     * was readable at fire time (same key the agenda widget uses); pre-unlock
     * alarms only carry the lightweight event id + start/end extras (see
     * ReminderReceiver), which are stashed as a [ReminderFallback] and resolved
     * against the provider once the app is open.
     */
    private fun consumeInstanceLink(it: Intent): Boolean {
        it.getStringExtra(ReminderScheduler.EXTRA_INSTANCE)?.let { raw ->
            runCatching { Json.decodeFromString<Instance>(raw) }.getOrNull()
        }?.let { instance ->
            pendingRoute.value = Route.Event(instance)
            pendingReminderFallback.value = null
            return finishInstanceLink(it)
        }
        if (it.hasExtra(ReminderScheduler.EXTRA_EVENT_ID) &&
            it.hasExtra(ReminderScheduler.EXTRA_INSTANCE_START)
        ) {
            val eventId = it.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
            val start = it.getLongExtra(ReminderScheduler.EXTRA_INSTANCE_START, 0L)
            val end = it.getLongExtra(ReminderScheduler.EXTRA_INSTANCE_END, 0L)
            if (eventId != -1L && start != 0L) {
                pendingRoute.value = null
                pendingReminderFallback.value = ReminderFallback(eventId, start, end)
                return finishInstanceLink(it)
            }
        }
        if (!it.hasExtra(ReminderScheduler.EXTRA_INSTANCE) &&
            !it.hasExtra(ReminderScheduler.EXTRA_EVENT_ID)
        ) {
            return false
        }
        // Malformed deep-link (exported activity): fall back to home, never crash.
        pendingRoute.value = null
        pendingReminderFallback.value = null
        return finishInstanceLink(it)
    }

    private fun finishInstanceLink(it: Intent): Boolean {
        // Instance intents carry no stream data; avoid falling into ICS handling.
        return it.data == null && IntentHelper.getUrisFromIntent(it).isEmpty()
    }

    /** Handles an ACTION_INSERT event intent from another app. Returns true when consumed. */
    private fun consumeInsertIntent(it: Intent): Boolean {
        if (it.action != Intent.ACTION_INSERT ||
            (it.type != "vnd.android.cursor.dir/event" && it.type != null)
        ) {
            return false
        }
        pendingRoute.value = editEventFromIntent(it)
        pendingReminderFallback.value = null
        return true
    }

    /** Routes file intents to the ICS import screen when they contain events. */
    private fun consumeImportUris(it: Intent) {
        val uris = IntentHelper.getUrisFromIntent(it)
        if (uris.isEmpty()) return
        // Persist read access so the import screen can re-open the files later.
        // Best-effort: non-persistable grants throw, the one-shot grant still covers
        // the immediate import.
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        // Decide off the main thread whether this is a real import: parse the
        // file(s) and only route to the import screen when they contain events.
        // Empty/eventless ICS files fall through and open the calendar on today,
        // so the import screen never flashes. Large files must not ANR.
        lifecycleScope.launch(Dispatchers.IO) {
            val withEvents = uris.filter { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { iS ->
                        parseICSFile(iS).isNotEmpty()
                    } == true
                } catch (expected: Exception) {
                    Log.error("MainActivity", "Error reading ICS file: $uri", expected)
                    false
                }
            }
            if (withEvents.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    importUris.value = withEvents.map { uri -> uri.toString() }
                }
            }
        }
    }
}

/** Builds a [Route.EditEvent] from an ACTION_INSERT intent's extras. */
private fun editEventFromIntent(it: Intent): Route.EditEvent {
    return Route.EditEvent(
        id = null,
        title = it.getStringExtra(CalendarContract.Events.TITLE),
        description = it.getStringExtra(CalendarContract.Events.DESCRIPTION),
        location = it.getStringExtra(CalendarContract.Events.EVENT_LOCATION),
        beginTime = it.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
            .takeIf { value -> value != NO_TIME_MILLIS },
        endTime = it.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L)
            .takeIf { value -> value != NO_TIME_MILLIS },
        allDay = it.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            .takeIf { _ -> it.hasExtra(CalendarContract.EXTRA_EVENT_ALL_DAY) },
    )
}

private const val NO_TIME_MILLIS = -1L

/**
 * Lightweight deep-link for a reminder tap when the full Instance JSON was
 * unavailable (pre-unlock alarm). Resolved against the provider once readable.
 */
data class ReminderFallback(val eventId: Long, val instanceStart: Long, val instanceEnd: Long)

/**
 * The activity's content, extracted from [MainActivity.onCreate] so the activity
 * method stays under the LongMethod/CyclomaticComplexMethod limits.
 */
@Composable
private fun MainActivity.CalendarAppContent(
    activityIntent: Intent?,
    importUris: MutableState<List<String>>,
    pendingRoute: MutableState<Route?>,
    pendingReminderFallback: MutableState<ReminderFallback?>,
    pendingDate: MutableState<LocalDate?>,
) {
    AppPermissionsGate(
        spec = AppPermissionsSpec(
            title = stringResource(R.string.please_grant_calendar_permission),
            requirements = listOf(
                PermissionRequirement.Runtime(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                    ),
                ),
                // Reminder notifications rely on exact alarms.
                PermissionRequirement.ExactAlarms,
                // Reminder notifications need POST_NOTIFICATIONS on
                // Android 13+; now blocking like everything else.
                PermissionRequirement.notifications(),
            ),
        ),
    ) {
            val viewModel: CalendarViewModel = viewModel()
            DateJumpEffect(activityIntent, pendingDate, viewModel)

            val uris by importUris
            val pending by pendingRoute
            val fallback by pendingReminderFallback
            val initialRoute = resolveInitialRoute(activityIntent, uris, pending, fallback)
            val context = LocalContext.current
            LaunchedEffect(fallback, pending) {
                // Stashed fallback (no full Instance yet): resolve it against the
                // provider now that permissions are granted and the app is open.
                // Provider query goes on IO — never block composition.
                val fb = fallback
                if (pending == null && fb != null) {
                    val appContext = context.applicationContext
                    val instance = withContext(Dispatchers.IO) {
                        resolveReminderFallback(appContext, fb)
                    }
                    instance?.let { pendingRoute.value = Route.Event(it) }
                    pendingReminderFallback.value = null
                }
            }
            Box(
                Modifier.fillMaxSize().onFileDrop { dropped ->
                    importUris.value = dropped.map { it.toString() }
                },
            ) {
                Navigation(viewModel, initialRoute) { importUris.value = emptyList() }
            }
        }
}

/**
 * Time/date-jump VIEW intents. The provider often sends these with a null
 * type, so the URI shape is matched too: without this the date-jump never
 * runs and the URI falls into the ICS import path.
 */
private fun isTimeEpochIntent(it: Intent): Boolean {
    if (it.action != Intent.ACTION_VIEW) return false
    if (it.type == "time/epoch") return true
    if (it.type != null) return false
    val data = it.data ?: return false
    if (data.lastPathSegment?.toLongOrNull() == null) return false
    return data.authority == "com.android.calendar" || data.path?.contains("time") == true
}

/**
 * Applies a pending date jump (or a time-epoch VIEW intent) to the ViewModel.
 * The provider often sends time URIs with a null type, so URI shape is matched
 * (see [MainActivity.isTimeEpochIntent]), not just type == "time/epoch".
 */
@Composable
private fun DateJumpEffect(
    activityIntent: Intent?,
    pendingDate: MutableState<LocalDate?>,
    viewModel: CalendarViewModel,
) {
    val dateJump by pendingDate
    LaunchedEffect(activityIntent, dateJump) {
        val target = dateJump ?: resolveDateJumpTarget(activityIntent)
        if (target != null) {
            viewModel.setSelectedDate(target)
            viewModel.setLastViewedDate(target)
            pendingDate.value = null
        }
    }
}

/** Extracts the jump-to date from a time-epoch VIEW intent, or null. */
private fun resolveDateJumpTarget(activityIntent: Intent?): LocalDate? {
    if (activityIntent == null) return null
    if (!isTimeEpochIntent(activityIntent)) return null
    return activityIntent.data?.lastPathSegment?.toLongOrNull()?.toJumpDate()
}

private fun Long.toJumpDate(): LocalDate? {
    return runCatching {
        Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).date
    }.getOrNull()
}

/**
 * Resolves a pre-unlock notification tap ([ReminderFallback]) against the
 * calendar provider. Null when the event is gone or the provider is still
 * unreadable — the caller then leaves the user on home (old behavior).
 */
private fun resolveReminderFallback(context: Context, fb: ReminderFallback): Instance? {
    return Instance.findForReminder(context, fb.eventId, fb.instanceStart, fb.instanceEnd)
}

/**
 * Resolves which route the app opens on: an ICS import, a pending deep-link, an
 * instance extra (widget or notification tap), a lightweight reminder fallback,
 * or an ACTION_INSERT — else the calendar home (null).
 * Exported activity: malformed extras fall back to no route instead of crashing.
 */
private fun resolveInitialRoute(
    activityIntent: Intent?,
    uris: List<String>,
    pending: Route?,
    fallback: ReminderFallback? = null,
): Route? {
    return when {
        uris.isNotEmpty() -> Route.Settings.ImportIcs(uris)
        pending != null -> pending
        // Pre-unlock notification tap stashed before composition: resolved
        // against the provider by the LaunchedEffect in CalendarAppContent.
        // Null here is temporary until that resolution lands.
        fallback != null -> null
        activityIntent == null -> null
        else -> routeFromIntent(activityIntent)
    }
}

/** Route carried directly by [activityIntent]: an instance deep-link or an ACTION_INSERT, else null. */
private fun routeFromIntent(activityIntent: Intent): Route? {
    activityIntent.getStringExtra(ReminderScheduler.EXTRA_INSTANCE)?.let { raw ->
        runCatching { Json.decodeFromString<Instance>(raw) }.getOrNull()
    }?.let { return Route.Event(it) }
    if (activityIntent.action == Intent.ACTION_INSERT &&
        (activityIntent.type == "vnd.android.cursor.dir/event" || activityIntent.type == null)
    ) {
        return editEventFromIntent(activityIntent)
    }
    return null
}


/** Navigation keys for calendar screens and dialogs, serializable so the back stack can be restored. */
sealed interface Route: NavKey {
    @Serializable
    /** Calendar home destination. */
    data object Calendar: Route {
        @Serializable
        /** Go-to-date dialog over the calendar home, initially showing the given viewing date. */
        data class GotoDialog(val dateViewing: LocalDate): Route
    }

    @Serializable
    /** Settings destination and parent of the settings dialog destinations. */
    data object Settings: Route {
        @Serializable
        /** Colour-picker dialog for the calendar with the given id. */
        data class ChangeColor(val id: Long): Route

        @Serializable
        /** Dialog for creating a new local calendar. */
        data class AddCalendar(val placeholder: Int = 0): Route

        @Serializable
        /** Dialog for renaming the calendar with the given id. */
        data class RenameCalendar(val id: Long): Route

        @Serializable
        /** Confirmation dialog for deleting the calendar with the given id. */
        data class DeleteCalendar(val id: Long): Route

        @Serializable
        /** Dialog for editing default reminders of the calendar with the given id. */
        data class DefaultReminders(val id: Long): Route

        @Serializable
        /** Holiday-calendar picker destination under Settings. */
        data object HolidayCalendars: Route

        @Serializable
        /** ICS import destination for the files at the given URIs. */
        data class ImportIcs(val uris: List<String>): Route
    }

    @Serializable
    /** Event detail destination for one occurrence of an event. */
    data class Event(val instance: Instance): Route

    @Serializable
    /** Event create/edit destination; a null id creates a new event, otherwise the event with that id is edited. */
    data class EditEvent(
        val id: Long?,
        val title: String? = null,
        val description: String? = null,
        val location: String? = null,
        val beginTime: Long? = null,
        val endTime: Long? = null,
        val allDay: Boolean? = null,
        /**
         * The instance the user opened, when they arrived here from its detail screen.
         *
         * The title morph needs it: a title is keyed per *instance* so that a recurring event
         * showing several times on the grid still has one origin per key, while everything else
         * about an edit is keyed per event. Null when the edit was not reached from a detail screen.
         */
        val instanceId: Long? = null,
    ): Route {
        @Serializable
        /** Date-picker dialog that returns its result under its key, starting at the initial date. */
        data class DatePickerDialog(val key: String, val initialDate: LocalDate, val minDate: LocalDate? = null): Route

        @Serializable
        /** Time-picker dialog that returns its result under its key, starting at the initial time. */
        data class TimePickerDialog(val key: String, val initialTime: LocalTime, val minTime: LocalTime? = null): Route

        @Serializable
        /** Calendar-picker dialog that returns its result under its key. */
        data class CalendarPickerDialog(val key: String): Route
        @Serializable
        /** Timezone-picker dialog that returns its result under its key. */
        data class TimezonePickerDialog(val key: String): Route

        @Serializable
        /** Recurrence-editor dialog returning its result under its key for an event starting on the start date. */
        data class RecurrenceDialog(
            val key: String,
            val startDate: LocalDate,
            val initial: RecurrenceParams? = null,
            val initialDates: List<LocalDate> = emptyList(),
        ): Route
    }
}

@Composable
fun Navigation(viewModel: CalendarViewModel, initialRoute: Route?, onImportClear: () -> Unit = {}) {
    val backStack = rememberNavBackStack(listOfNotNull(Route.Calendar, initialRoute))
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    LaunchedEffect(initialRoute) {
        if(initialRoute != null) {
            backStack.reset(Route.Calendar, initialRoute)
        } else {
            backStack.reset(Route.Calendar)
        }
    }

    // Clear import URIs when leaving ImportIcs screen
    LaunchedEffect(backStack.backStack) {
        if (backStack.backStack.lastOrNull() !is Route.Settings.ImportIcs) {
            onImportClear()
        }
    }

    MainNavigation(backStack) {
        mainEntries(viewModel, backStack)
        dialogEntries(backStack)
        settingsDialogEntries(viewModel, backStack)
    }
}

private fun EntryProviderScope<Route>.mainEntries(
    viewModel: CalendarViewModel,
    backStack: NavBackStack<Route>,
) {
    entry<Route.Calendar>(metadata = ListPage()) {
        CalendarScreen(viewModel, backStack)
    }
    // Morph: the event's title travels out of the chip the user tapped on the grid.
    entry<Route.Event>(metadata = ListDetailPage() + MorphPage()) { key ->
        EventScreen(viewModel, key.instance, backStack)
    }
    entry<Route.Settings> {
        SettingsScreen(viewModel, backStack)
    }
    entry<Route.Settings.HolidayCalendars> {
        HolidayCalendarsScreen(viewModel, backStack)
    }
    // Morph: the event's location line grows into the location field.
    entry<Route.EditEvent>(metadata = ListDetailPage() + MorphPage()) { key ->
        EditEventScreen(viewModel, key, backStack)
    }
    entry<Route.Settings.ImportIcs> { key ->
        ImportIcsScreen(viewModel, backStack, key.uris)
    }
}

private fun EntryProviderScope<Route>.dialogEntries(
    backStack: NavBackStack<Route>,
) {
    entry<Route.Calendar.GotoDialog>(metadata = DialogPage()) { key ->
        CalendarSetDateDialog(backStack, key.dateViewing)
    }

    entry<Route.EditEvent.DatePickerDialog>(metadata = DialogPage()) { key ->
        DatePickerDialog(backStack, key.key, key.initialDate, key.minDate)
    }

    entry<Route.EditEvent.TimePickerDialog>(metadata = DialogPage()) { key ->
        TimePickerDialogContent(backStack, key.key, key.initialTime, key.minTime)
    }

    entry<Route.EditEvent.CalendarPickerDialog>(metadata = DialogPage()) { key ->
        CalendarPickerDialog(backStack, key.key)
    }

    entry<Route.EditEvent.TimezonePickerDialog>(metadata = DialogPage()) { key ->
        TimezonePickerDialog(backStack, key.key)
    }

    entry<Route.EditEvent.RecurrenceDialog>(metadata = DialogPage()) { key ->
        RecurrenceDialog(backStack, key.key, key.startDate, key.initial, key.initialDates)
    }
}

private fun EntryProviderScope<Route>.settingsDialogEntries(
    viewModel: CalendarViewModel,
    backStack: NavBackStack<Route>,
) {
    entry<Route.Settings.ChangeColor>(metadata = DialogPage()) { key ->
        SettingsChangeColorDialog(viewModel, backStack, key.id)
    }

    entry<Route.Settings.AddCalendar>(metadata = DialogPage()) { _ ->
        SettingsAddCalendarDialog(viewModel, backStack)
    }

    entry<Route.Settings.RenameCalendar>(metadata = DialogPage()) { key ->
        SettingsRenameCalendarDialog(viewModel, backStack, key.id)
    }

    entry<Route.Settings.DeleteCalendar>(metadata = DialogPage()) { key ->
        SettingsDeleteCalendarDialog(viewModel, backStack, key.id)
    }

    entry<Route.Settings.DefaultReminders>(metadata = DialogPage()) { key ->
        SettingsDefaultRemindersDialog(viewModel, backStack, key.id)
    }
}
