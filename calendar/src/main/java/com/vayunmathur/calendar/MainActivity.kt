package com.vayunmathur.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.calendar.ui.CalendarScreen
import com.vayunmathur.calendar.ui.dialog.CalendarSetDateDialog
import com.vayunmathur.calendar.ui.EditEventScreen
import com.vayunmathur.calendar.ui.EventScreen
import com.vayunmathur.calendar.ui.SettingsAddCalendarDialog
import com.vayunmathur.calendar.ui.SettingsChangeColorDialog
import com.vayunmathur.calendar.ui.SettingsDeleteCalendarDialog
import com.vayunmathur.calendar.ui.SettingsRenameCalendarDialog
import com.vayunmathur.calendar.ui.SettingsScreen
import com.vayunmathur.calendar.ui.dialog.CalendarPickerDialog
import com.vayunmathur.calendar.ui.dialog.DatePickerDialog
import com.vayunmathur.calendar.ui.dialog.RecurrenceDialog
import com.vayunmathur.calendar.ui.dialog.TimePickerDialogContent
import com.vayunmathur.calendar.ui.dialog.TimezonePickerDialog
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.util.reset
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DialogPage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            var hasPermissions by remember { mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) }
            DynamicTheme {
                if (!hasPermissions) {
                    NoPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    if(intent.hasExtra("instance")) {
                        Navigation(Json.decodeFromString<Instance>(intent.getStringExtra("instance")!!))
                    } else {
                        Navigation(null)
                    }
                }
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
    }

    @Serializable
    data class Event(val instance: Instance): Route

    @Serializable
    data class EditEvent(val id: Long?): Route {
        // Dialog routes for date/time picking that are specific to the EditEvent page
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
fun Navigation(instance: Instance?) {
    val viewModel: ContactViewModel = viewModel()
    val backStack = rememberNavBackStack(listOfNotNull(Route.Calendar, instance?.let {Route.Event(it)}))
    LaunchedEffect(instance) {
        if(instance != null) {
            backStack.reset(Route.Calendar, Route.Event(instance))
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
        entry<Route.EditEvent> { key ->
            EditEventScreen(viewModel, key.id, backStack)
        }

        entry<Route.Calendar.GotoDialog>(metadata = DialogPage()) { key ->
            CalendarSetDateDialog(backStack, key.dateViewing)
        }

        // Dialog entries for the new date/time pickers (nested under EditEvent)
        entry<Route.EditEvent.DatePickerDialog>(metadata = DialogPage()) { key ->
            DatePickerDialog(backStack, key.key, key.initialDate, key.minDate)
        }

        entry<Route.EditEvent.TimePickerDialog>(metadata = DialogPage()) { key ->
            // initialTime is already a LocalTime? so pass directly, along with optional minTime
            TimePickerDialogContent(backStack, key.key, key.initialTime, key.minTime)
        }

        entry<Route.EditEvent.CalendarPickerDialog>(metadata = DialogPage()) { key ->
            CalendarPickerDialog(backStack, key.key)
        }

        entry<Route.EditEvent.TimezonePickerDialog>(metadata = DialogPage()) { key ->
            // show timezone selection dialog
            TimezonePickerDialog(backStack, key.key)
        }

        entry<Route.EditEvent.RecurrenceDialog>(metadata = DialogPage()) { key ->
            // show recurrence picker dialog; RecurrenceParams is passed as initial value optionally
            RecurrenceDialog(backStack, key.key, key.startDate, key.initial)
        }

        // Settings-related dialog entries
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