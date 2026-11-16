package com.vayunmathur.parentalcontrols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel
import com.vayunmathur.parentalcontrols.ui.AppLimitsActions
import com.vayunmathur.parentalcontrols.ui.AppLimitsScreen
import com.vayunmathur.parentalcontrols.ui.BedtimeActions
import com.vayunmathur.parentalcontrols.ui.BedtimeScreen
import com.vayunmathur.parentalcontrols.ui.DailyLimitActions
import com.vayunmathur.parentalcontrols.ui.DailyLimitScreen
import com.vayunmathur.parentalcontrols.ui.DashboardActions
import com.vayunmathur.parentalcontrols.ui.DashboardScreen
import com.vayunmathur.parentalcontrols.ui.PinGateActions
import com.vayunmathur.parentalcontrols.ui.PinGateScreen
import com.vayunmathur.parentalcontrols.ui.PinSetupActions
import com.vayunmathur.parentalcontrols.ui.PinSetupScreen
import com.vayunmathur.parentalcontrols.ui.WebContentFiltersScreen
import com.vayunmathur.parentalcontrols.ui.WindowScheduleActions
import com.vayunmathur.parentalcontrols.ui.WindowScheduleScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * Navigation host for Parental Controls, with the PIN gate as a destination.
 *
 * Gate semantics carry over from the activity-per-screen design unchanged:
 * a gated editor is never on the stack under its gate (verification
 * replaces [Route.PinGate] with the destination, cancel pops or finishes),
 * a fresh install goes through [Route.PinSetup] first and only then the
 * gate, and backgrounding the host after content was verified re-challenges
 * on resume (see the lifecycle observer below). PinGateActivity remains a
 * separate activity for the lock screens, which start it for a result from
 * outside this host; it renders the same [PinGateScreen].
 */
@Composable
fun Navigation(
    viewModel: SupervisionViewModel,
    initialRoute: Route = Route.Dashboard,
    externalRoute: Route? = null,
    onExternalRouteConsumed: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val context = LocalContext.current
    val pin = remember(context) { ParentPin.get(context) }
    val scope = rememberCoroutineScope()

    val initialTarget = PinGateTarget.forRoute(initialRoute)
    val backStack = when {
        initialTarget != null && pin.isSet() ->
            rememberNavBackStack<Route>(Route.PinGate(initialTarget))
        initialTarget != null ->
            rememberNavBackStack<Route>(Route.PinSetup)
        initialRoute == Route.PinSetup ->
            rememberNavBackStack<Route>(Route.Dashboard, Route.PinSetup)
        initialRoute == Route.Dashboard ->
            rememberNavBackStack<Route>(Route.Dashboard)
        else -> rememberNavBackStack<Route>(Route.Dashboard, initialRoute)
    }

    // Non-screen continuations for the gate/setup destinations: what a passed
    // gate applies (the pending master-switch value) and which gate a finished
    // setup hands over to. Neither chooses a screen; the routes above do.
    var pinVerified by remember { mutableStateOf(false) }
    var pendingControls by remember { mutableStateOf<Boolean?>(null) }
    var pendingAfterSetup by remember { mutableStateOf(initialTarget) }
    var backgroundedAfterContent by remember { mutableStateOf(false) }

    fun requestGated(target: PinGateTarget) {
        pinVerified = false
        if (pin.isSet()) {
            backStack.add(Route.PinGate(target))
        } else {
            pendingAfterSetup = target
            backStack.add(Route.PinSetup)
        }
    }

    fun requestSetup(after: PinGateTarget?) {
        pendingAfterSetup = after
        backStack.add(Route.PinSetup)
    }

    fun leaveGate() {
        if (backStack.backStack.size <= 1) onFinish() else backStack.pop()
    }

    // Re-challenge on return, mirroring the former PinGatedActivity: backgrounding after
    // verified content was shown revokes the verification, and a gated top
    // destination is swapped back to its gate before it can render again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (pinVerified) backgroundedAfterContent = true
                Lifecycle.Event.ON_RESUME -> if (backgroundedAfterContent) {
                    backgroundedAfterContent = false
                    pinVerified = false
                    val top = backStack.last()
                    PinGateTarget.forRoute(top)?.let { backStack.setLast(Route.PinGate(it)) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(externalRoute) {
        val route = externalRoute ?: return@LaunchedEffect
        when {
            route == Route.Dashboard -> backStack.reset(Route.Dashboard)
            PinGateTarget.forRoute(route) != null ->
                requestGated(PinGateTarget.forRoute(route)!!)
            route == Route.PinSetup -> requestSetup(null)
            else -> backStack.add(route)
        }
        onExternalRouteConsumed()
    }

    MainNavigation(backStack) {
        entry<Route.Dashboard>(metadata = ListPage()) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            DashboardScreen(
                state = state,
                actions = DashboardActions(
                    onSetControls = { desired ->
                        if (!pin.isSet()) {
                            requestSetup(null)
                        } else {
                            pendingControls = desired
                            backStack.add(Route.PinGate(PinGateTarget.Controls))
                        }
                    },
                    onOpenDailyLimit = { requestGated(PinGateTarget.DailyLimit) },
                    onOpenAppLimits = { requestGated(PinGateTarget.AppLimits) },
                    onOpenDowntime = { requestGated(PinGateTarget.Downtime) },
                    onOpenWebFilters = { requestGated(PinGateTarget.WebFilters) },
                    onOpenPin = {
                        if (!pin.isSet()) {
                            requestSetup(null)
                        } else {
                            backStack.add(Route.PinGate(PinGateTarget.ManagePin))
                        }
                    },
                ),
            )
        }
        entry<Route.DailyLimit>(metadata = DetailPage()) {
            if (!pinVerified) { LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(PinGateTarget.DailyLimit)) }; return@entry }
            val state by viewModel.state.collectAsStateWithLifecycle()
            DailyLimitScreen(
                currentMinutes = state.dailyLimitMinutes,
                actions = DailyLimitActions(onLimitChange = viewModel::setDeviceDailyLimit),
            )
        }
        entry<Route.AppLimits>(metadata = DetailPage()) {
            if (!pinVerified) { LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(PinGateTarget.AppLimits)) }; return@entry }
            val state by viewModel.state.collectAsStateWithLifecycle()
            AppLimitsScreen(
                state = state,
                actions = AppLimitsActions(onLimitChange = viewModel::setDailyLimit),
            )
        }
        entry<Route.Bedtime>(metadata = DetailPage()) {
            if (!pinVerified) { LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(PinGateTarget.Bedtime)) }; return@entry }
            val state by viewModel.state.collectAsStateWithLifecycle()
            BedtimeScreen(
                state = state,
                actions = BedtimeActions(
                    onEnabledChange = viewModel::setScheduleEnabled,
                    onStartChange = viewModel::setStart,
                    onEndChange = viewModel::setEnd,
                    onToggleDay = viewModel::toggleDay,
                    onAppBedtimeChange = viewModel::setBedtimeBlocked,
                ),
            )
        }
        entry<Route.Downtime>(metadata = DetailPage()) {
            if (!pinVerified) { LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(PinGateTarget.Downtime)) }; return@entry }
            val state by viewModel.state.collectAsStateWithLifecycle()
            WindowScheduleScreen(
                title = stringResource(R.string.downtime_title),
                enableLabel = stringResource(R.string.downtime_enable),
                enableHint = stringResource(R.string.downtime_enable_hint),
                startLabel = stringResource(R.string.schedule_start),
                endLabel = stringResource(R.string.schedule_end),
                daysLabel = stringResource(R.string.schedule_days),
                appsLabel = stringResource(R.string.downtime_apps),
                dayInitials = stringResource(R.string.bedtime_day_initials),
                enabled = state.downtime.enabled,
                startMinute = state.downtime.startMinute,
                endMinute = state.downtime.endMinute,
                daysMask = state.downtime.daysMask,
                apps = state.apps,
                loading = state.loading,
                actions = WindowScheduleActions(
                    onEnabledChange = viewModel::setDowntimeEnabled,
                    onStartChange = viewModel::setDowntimeStart,
                    onEndChange = viewModel::setDowntimeEnd,
                    onToggleDay = viewModel::toggleDowntimeDay,
                    onAppAllowChange = viewModel::setAllowedInDowntime,
                ),
            )
        }
        entry<Route.SchoolTime>(metadata = DetailPage()) {
            if (!pinVerified) { LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(PinGateTarget.SchoolTime)) }; return@entry }
            val state by viewModel.state.collectAsStateWithLifecycle()
            WindowScheduleScreen(
                title = stringResource(R.string.school_title),
                enableLabel = stringResource(R.string.school_enable),
                enableHint = stringResource(R.string.school_enable_hint),
                startLabel = stringResource(R.string.schedule_start),
                endLabel = stringResource(R.string.schedule_end),
                daysLabel = stringResource(R.string.schedule_days),
                appsLabel = stringResource(R.string.school_apps),
                dayInitials = stringResource(R.string.bedtime_day_initials),
                enabled = state.schoolTime.enabled,
                startMinute = state.schoolTime.startMinute,
                endMinute = state.schoolTime.endMinute,
                daysMask = state.schoolTime.daysMask,
                apps = state.apps,
                loading = state.loading,
                actions = WindowScheduleActions(
                    onEnabledChange = viewModel::setSchoolTimeEnabled,
                    onStartChange = viewModel::setSchoolTimeStart,
                    onEndChange = viewModel::setSchoolTimeEnd,
                    onToggleDay = viewModel::toggleSchoolTimeDay,
                    onAppAllowChange = viewModel::setAllowedInDowntime,
                ),
            )
        }
        entry<Route.WebFilters>(metadata = DetailPage()) {
            if (!pinVerified) { LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(PinGateTarget.WebFilters)) }; return@entry }
            WebContentFiltersScreen()
        }
        entry<Route.PinSetup>(metadata = DetailPage()) {
            PinSetupScreen(
                actions = PinSetupActions(
                    onSet = { entry ->
                        val after = pendingAfterSetup
                        pendingAfterSetup = null
                        scope.launch {
                            withContext(Dispatchers.IO) { pin.set(entry) }
                            if (after != null) {
                                backStack.setLast(Route.PinGate(after))
                            } else if (backStack.backStack.size <= 1) {
                                backStack.reset(Route.Dashboard)
                            } else {
                                backStack.pop()
                            }
                        }
                        true
                    },
                    onCancel = { leaveGate() },
                ),
            )
        }
        entry<Route.PinGate>(metadata = DetailPage()) { route ->
            var error by remember(route) { mutableStateOf<String?>(null) }
            var lockedOut by remember(route) { mutableStateOf(pin.isLockedOut()) }
            val wrongPin = stringResource(R.string.pin_wrong)
            val lockedOutText = stringResource(R.string.pin_locked_out)
            DisposableEffect(route) {
                onDispose {
                    // Leaving without verifying (cancel, system back) drops the
                    // pending gated action; a later gate must not apply it.
                    if (!pinVerified) pendingControls = null
                }
            }
            PinGateScreen(
                error = error,
                lockedOut = lockedOut,
                actions = PinGateActions(
                    onVerify = { entry ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { pin.verify(entry) }
                            if (ok) {
                                pinVerified = true
                                when (route.target) {
                                    PinGateTarget.Controls -> {
                                        pendingControls?.let { viewModel.setControlsEnabled(it) }
                                        pendingControls = null
                                        if (backStack.backStack.size <= 1) {
                                            backStack.reset(Route.Dashboard)
                                        } else {
                                            backStack.pop()
                                        }
                                    }
                                    PinGateTarget.ManagePin -> {
                                        pendingAfterSetup = null
                                        backStack.setLast(Route.PinSetup)
                                    }
                                    else -> backStack.setLast(route.target.editorRoute()!!)
                                }
                            } else {
                                lockedOut = pin.isLockedOut()
                                error = if (lockedOut) lockedOutText else wrongPin
                            }
                        }
                        // The real answer arrives asynchronously; false keeps the entry.
                        false
                    },
                    onCancel = { leaveGate() },
                ),
            )
        }
    }
}
