package com.vayunmathur.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.glance.CalendarGlanceWidgetReceiver
import com.vayunmathur.calendar.ui.*
import com.vayunmathur.calendar.ui.dialogs.*
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.*
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    private val importUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateWidgetPreviews(CalendarGlanceWidgetReceiver::class)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            var hasPermissions by remember { mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) }
            val dataStore = remember { DataStoreUtils.getInstance(this) }
            val themeName by dataStore.stringFlow("theme_mode").collectAsState(initial = dataStore.getString("theme_mode"))
            val darkTheme = when (themeName?.let { runCatching { CalendarViewModel.ThemeMode.valueOf(it) }.getOrNull() }) {
                CalendarViewModel.ThemeMode.Light -> false
                CalendarViewModel.ThemeMode.Dark -> true
                else -> null
            }
            DynamicTheme(darkTheme) {
                if (!hasPermissions) {
                    NoPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    val viewModel: CalendarViewModel = viewModel()

                    LaunchedEffect(intent) {
                        if (intent?.action == Intent.ACTION_VIEW && intent.type == "time/epoch") {
                            intent.data?.lastPathSegment?.toLongOrNull()?.let { timestamp ->
                                val date = Instant.fromEpochMilliseconds(timestamp)
                                    .toLocalDateTime(TimeZone.currentSystemDefault()).date
                                viewModel.setSelectedDate(date)
                                viewModel.setLastViewedDate(date)
                            }
                        }
                    }
                    
                    val uris by importUris
                    if (uris.isNotEmpty()) {
                        ImportIcsDialog(viewModel, uris) { importUris.value = emptyList() }
                    }

                    val initialRoute = when {
                        intent.hasExtra("instance") -> {
                            Route.Event(Json.decodeFromString<Instance>(intent.getStringExtra("instance")!!))
                        }
                        intent.action == Intent.ACTION_INSERT && (intent.type == "vnd.android.cursor.dir/event" || intent.type == null) -> {
                            Route.EditEvent(
                                id = null,
                                title = intent.getStringExtra(CalendarContract.Events.TITLE),
                                description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION),
                                location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION),
                                beginTime = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L).takeIf { it != -1L },
                                endTime = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L).takeIf { it != -1L },
                                allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false).takeIf { intent.hasExtra(CalendarContract.EXTRA_EVENT_ALL_DAY) }
                            )
                        }
                        else -> null
                    }
                    Box(Modifier.fillMaxSize().onFileDrop { importUris.value = it }) {
                        Navigation(viewModel, initialRoute)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            // Only handle URIs if they are actually intended for import (ignore time/epoch VIEW intents)
            if (it.action == Intent.ACTION_VIEW && it.type == "time/epoch") return

            val uris = IntentHelper.getUrisFromIntent(it)
            if (uris.isNotEmpty()) {
                importUris.value = uris
            }
        }
    }
}

@Composable
fun NoPermissionsScreen(permissions: Array<String>, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            Button(
                {
                    permissionRequestor.launch(permissions)
                }, Modifier.align(Alignment.Center)
            ) {
                Text(text = "Please grant calendar permission")
            }
        }
    }
}

sealed interface Route: NavKey {
    @Serializable
    data object Calendar: Route {
        @Serializable
        data class GotoDialog(val dateViewing: LocalDate): Route
    }

    @Serializable
    data object Settings: Route {
        @Serializable
        data class ChangeColor(val id: Long): Route

        @Serializable
        data class AddCalendar(val placeholder: Int = 0): Route

        @Serializable
        data class RenameCalendar(val id: Long): Route

        @Serializable
        data class DeleteCalendar(val id: Long): Route

        @Serializable
        data object HolidayCalendars: Route
    }

    @Serializable
    data class Event(val instance: Instance): Route

    @Serializable
    data class EditEvent(
        val id: Long?,
        val title: String? = null,
        val description: String? = null,
        val location: String? = null,
        val beginTime: Long? = null,
        val endTime: Long? = null,
        val allDay: Boolean? = null
    ): Route {
        @Serializable
        data class DatePickerDialog(val key: String, val initialDate: LocalDate, val minDate: LocalDate? = null): Route

        @Serializable
        data class TimePickerDialog(val key: String, val initialTime: LocalTime, val minTime: LocalTime? = null): Route

        @Serializable
        data class CalendarPickerDialog(val key: String): Route
        @Serializable
        data class TimezonePickerDialog(val key: String): Route

        @Serializable
        data class RecurrenceDialog(val key: String, val startDate: LocalDate, val initial: RecurrenceParams? = null): Route
    }
}

@Composable
fun Navigation(viewModel: CalendarViewModel, initialRoute: Route?) {
    val backStack = rememberNavBackStack(listOfNotNull(Route.Calendar, initialRoute))
    LaunchedEffect(initialRoute) {
        if(initialRoute != null) {
            backStack.reset(Route.Calendar, initialRoute)
        } else {
            backStack.reset(Route.Calendar)
        }
    }

    MainNavigation(backStack) {
        entry<Route.Calendar> {
            CalendarScreen(viewModel, backStack)
        }
        entry<Route.Event> { key ->
            EventScreen(viewModel, key.instance, backStack)
        }
        entry<Route.Settings> {
            SettingsScreen(viewModel, backStack)
        }
        entry<Route.Settings.HolidayCalendars> {
            HolidayCalendarsScreen(viewModel, backStack)
        }
        entry<Route.EditEvent> { key ->
            EditEventScreen(viewModel, key, backStack)
        }

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
            RecurrenceDialog(backStack, key.key, key.startDate, key.initial)
        }

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
    }
}