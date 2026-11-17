package com.vayunmathur.calendar.ui

import android.Manifest
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.calendar.Navigation
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.platform.ReminderFallback
import com.vayunmathur.calendar.platform.resolveInitialRoute
import com.vayunmathur.calendar.platform.resolveReminderFallback
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.onFileDrop
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.withContext

/**
 * The activity's content, extracted from [MainActivity.onCreate] so the activity
 * method stays under the LongMethod/CyclomaticComplexMethod limits.
 */
@Composable
fun CalendarAppContent(
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
