package com.vayunmathur.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.ui.dialogEntries
import com.vayunmathur.calendar.ui.mainEntries
import com.vayunmathur.calendar.ui.settingsDialogEntries
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

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
