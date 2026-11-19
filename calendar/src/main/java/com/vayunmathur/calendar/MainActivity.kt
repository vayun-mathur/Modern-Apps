package com.vayunmathur.calendar

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.platform.ReminderFallback
import com.vayunmathur.calendar.platform.ReminderScheduler
import com.vayunmathur.calendar.platform.editEventFromIntent
import com.vayunmathur.calendar.platform.isTimeEpochIntent
import com.vayunmathur.calendar.ui.CalendarAppContent
import com.vayunmathur.calendar.ui.parseICSFile
import com.vayunmathur.calendar.widget.CalendarGlanceWidgetReceiver
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
