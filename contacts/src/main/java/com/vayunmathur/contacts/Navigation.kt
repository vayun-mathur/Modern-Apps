package com.vayunmathur.contacts

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.sync.CalendarWorker
import com.vayunmathur.contacts.ui.NavGraphEntries
import com.vayunmathur.contacts.ui.rememberTabsCallbacks
import com.vayunmathur.contacts.ui.resolveStartRoute
import com.vayunmathur.contacts.ui.shouldPushOnto
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.ui.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Navigation(
    viewModel: ContactViewModel,
    initialRoute: Route? = null,
    onExit: () -> Unit = {},
    onImportClear: () -> Unit = {},
) {
    // Deep-link handling: tab routes (ContactsList, GroupsList, Settings) are
    // real sibling destinations. A tab deep link is the start destination
    // itself; a groups deep-link's expand arg is remembered separately so the
    // start route can be the plain GroupsList tab (keeping it selected in the
    // bottom bar) while GroupsPage still expands that group. The App Info
    // entry point starts on the Settings tab.
    val groupsExpandId = (initialRoute as? Route.GroupsList)?.expandGroupId
    val activity = LocalActivity.current
    val launchedFromAppInfo = activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES
    val startRoute = resolveStartRoute(initialRoute, launchedFromAppInfo)
    val backStack = rememberNavBackStack<Route>(startRoute)

    LaunchedEffect(initialRoute) {
        // Warm deep links: a tab route resets the stack to that sibling (the
        // groups expand arg travels via groupsExpandId above); a non-tab route
        // pushes if it is not already on top.
        val pending = initialRoute ?: return@LaunchedEffect
        when (pending) {
            is Route.ContactsList -> backStack.reset(Route.ContactsList)
            is Route.GroupsList -> backStack.reset(Route.GroupsList())
            is Route.Settings -> backStack.reset(Route.Settings)
            else -> if (pending.shouldPushOnto(backStack)) backStack.add(pending)
        }
    }

    // Contacts is the home tab: system back from Groups/Settings returns
    // there instead of exiting the app (the pager's old behaviour). Back from
    // Contacts falls through to the nav host, which exits at the root.
    BackHandler(enabled = backStack.last() is Route.GroupsList || backStack.last() is Route.Settings) {
        backStack.reset(Route.ContactsList)
    }

    // Clear import URIs when leaving ImportVcf screen
    LaunchedEffect(backStack.backStack) {
        if (backStack.backStack.lastOrNull() !is Route.ImportVcf) {
            onImportClear()
        }
    }

    fun goBack() {
        if (backStack.backStack.size > 1) {
            backStack.pop()
        } else {
            onExit()
        }
    }

    val isCalendarSyncEnabled by viewModel.isCalendarSyncEnabled.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(isCalendarSyncEnabled) {
        if (isCalendarSyncEnabled) {
            com.vayunmathur.contacts.sync.CalendarWorker.schedule(context)
        }
    }

    val tabsCallbacks = rememberTabsCallbacks(backStack)
    NavGraphEntries(
        viewModel = viewModel,
        backStack = backStack,
        tabsCallbacks = tabsCallbacks,
        groupsExpandId = groupsExpandId,
        goBack = ::goBack,
    )
}
