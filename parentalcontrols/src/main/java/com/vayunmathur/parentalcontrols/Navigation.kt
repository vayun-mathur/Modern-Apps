package com.vayunmathur.parentalcontrols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.domain.PinGateTarget
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
import kotlinx.coroutines.CoroutineScope
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
    val backStack = rememberInitialBackStack(initialRoute, initialTarget, pin.isSet())

    // Non-screen continuations for the gate/setup destinations: what a passed
    // gate applies (the pending master-switch value) and which gate a finished
    // setup hands over to. Neither chooses a screen; the routes above do.
    val pinVerified = remember { mutableStateOf(false) }
    val pendingControls = remember { mutableStateOf<Boolean?>(null) }
    val pendingAfterSetup = remember { mutableStateOf(initialTarget) }
    val backgroundedAfterContent = remember { mutableStateOf(false) }

    RechallengeEffect(
        LocalLifecycleOwner.current, pinVerified, backgroundedAfterContent, backStack,
    )
    ExternalRouteEffect(
        externalRoute, backStack, pin, pinVerified, pendingAfterSetup, onExternalRouteConsumed,
    )

    MainNavigation(backStack) {
        dashboardEntry(viewModel, pin, backStack, pinVerified, pendingControls, pendingAfterSetup)
        gatedEntries(viewModel, pinVerified, backStack)
        pinSetupEntry(pin, scope, backStack, pendingAfterSetup, onFinish)
        pinGateEntry(viewModel, pin, scope, backStack, pinVerified, pendingControls, pendingAfterSetup, onFinish)
    }
}

/** The stack a cold start lands on: a gated route starts at its gate (or setup). */
@Composable
private fun rememberInitialBackStack(
    initialRoute: Route,
    initialTarget: PinGateTarget?,
    pinIsSet: Boolean,
): NavBackStack<Route> = when {
    initialTarget != null && pinIsSet ->
        rememberNavBackStack<Route>(Route.PinGate(initialTarget))
    initialTarget != null ->
        rememberNavBackStack<Route>(Route.PinSetup)
    initialRoute == Route.PinSetup ->
        rememberNavBackStack<Route>(Route.Dashboard, Route.PinSetup)
    initialRoute == Route.Dashboard ->
        rememberNavBackStack<Route>(Route.Dashboard)
    else -> rememberNavBackStack<Route>(Route.Dashboard, initialRoute)
}

/**
 * Re-challenge on return, mirroring the former PinGatedActivity: backgrounding after
 * verified content was shown revokes the verification, and a gated top
 * destination is swapped back to its gate before it can render again.
 */
@Composable
private fun RechallengeEffect(
    lifecycleOwner: LifecycleOwner,
    pinVerified: MutableState<Boolean>,
    backgroundedAfterContent: MutableState<Boolean>,
    backStack: NavBackStack<Route>,
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE ->
                    if (pinVerified.value) backgroundedAfterContent.value = true
                Lifecycle.Event.ON_RESUME -> if (backgroundedAfterContent.value) {
                    backgroundedAfterContent.value = false
                    pinVerified.value = false
                    val top = backStack.last()
                    PinGateTarget.forRoute(top)?.let { backStack.setLast(Route.PinGate(it)) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Applies a route handed in from outside the host, through the gate when gated. */
@Composable
private fun ExternalRouteEffect(
    externalRoute: Route?,
    backStack: NavBackStack<Route>,
    pin: ParentPin,
    pinVerified: MutableState<Boolean>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    onExternalRouteConsumed: () -> Unit,
) {
    LaunchedEffect(externalRoute) {
        val route = externalRoute ?: return@LaunchedEffect
        when {
            route == Route.Dashboard -> backStack.reset(Route.Dashboard)
            PinGateTarget.forRoute(route) != null ->
                requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.forRoute(route)!!)
            route == Route.PinSetup -> requestSetup(backStack, pendingAfterSetup, null)
            else -> backStack.add(route)
        }
        onExternalRouteConsumed()
    }
}

/** Opens [target] behind its gate, or behind PIN setup when no PIN exists yet. */
private fun requestGated(
    backStack: NavBackStack<Route>,
    pin: ParentPin,
    pinVerified: MutableState<Boolean>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    target: PinGateTarget,
) {
    pinVerified.value = false
    if (pin.isSet()) {
        backStack.add(Route.PinGate(target))
    } else {
        pendingAfterSetup.value = target
        backStack.add(Route.PinSetup)
    }
}

/** Opens PIN setup, handing over to [after]'s gate when it completes. */
private fun requestSetup(
    backStack: NavBackStack<Route>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    after: PinGateTarget?,
) {
    pendingAfterSetup.value = after
    backStack.add(Route.PinSetup)
}

/** Leaves a gate/setup destination: finishes the host at the graph root. */
private fun leaveGate(backStack: NavBackStack<Route>, onFinish: () -> Unit) {
    if (backStack.backStack.size <= 1) onFinish() else backStack.pop()
}

/** Redirects an unverified gated entry back to its gate before it can render. */
@Composable
private fun GateRedirect(pinVerified: Boolean, backStack: NavBackStack<Route>, target: PinGateTarget) {
    if (!pinVerified) {
        LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(target)) }
    }
}

/** The dashboard destination and the gated destinations it opens. */
private fun EntryProviderScope<Route>.dashboardEntry(
    viewModel: SupervisionViewModel,
    pin: ParentPin,
    backStack: NavBackStack<Route>,
    pinVerified: MutableState<Boolean>,
    pendingControls: MutableState<Boolean?>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
) {
    entry<Route.Dashboard>(metadata = ListPage()) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        DashboardScreen(
            state = state,
            actions = DashboardActions(
                onSetControls = { desired ->
                    if (!pin.isSet()) {
                        requestSetup(backStack, pendingAfterSetup, null)
                    } else {
                        pendingControls.value = desired
                        backStack.add(Route.PinGate(PinGateTarget.Controls))
                    }
                },
                onOpenDailyLimit = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.DailyLimit)
                },
                onOpenAppLimits = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.AppLimits)
                },
                onOpenDowntime = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.Downtime)
                },
                onOpenWebFilters = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.WebFilters)
                },
                onOpenPin = {
                    if (!pin.isSet()) {
                        requestSetup(backStack, pendingAfterSetup, null)
                    } else {
                        backStack.add(Route.PinGate(PinGateTarget.ManagePin))
                    }
                },
            ),
        )
    }
}

/** Every PIN-gated editor destination; each re-checks verification first. */
private fun EntryProviderScope<Route>.gatedEntries(
    viewModel: SupervisionViewModel,
    pinVerified: MutableState<Boolean>,
    backStack: NavBackStack<Route>,
) {
    entry<Route.DailyLimit>(metadata = DetailPage()) {
        GateRedirect(pinVerified.value, backStack, PinGateTarget.DailyLimit)
        if (pinVerified.value) DailyLimitEntry(viewModel)
    }
    entry<Route.AppLimits>(metadata = DetailPage()) {
        GateRedirect(pinVerified.value, backStack, PinGateTarget.AppLimits)
        if (pinVerified.value) AppLimitsEntry(viewModel)
    }
    entry<Route.Bedtime>(metadata = DetailPage()) {
        GateRedirect(pinVerified.value, backStack, PinGateTarget.Bedtime)
        if (pinVerified.value) BedtimeEntry(viewModel)
    }
    entry<Route.Downtime>(metadata = DetailPage()) {
        GateRedirect(pinVerified.value, backStack, PinGateTarget.Downtime)
        if (pinVerified.value) DowntimeEntry(viewModel)
    }
    entry<Route.SchoolTime>(metadata = DetailPage()) {
        GateRedirect(pinVerified.value, backStack, PinGateTarget.SchoolTime)
        if (pinVerified.value) SchoolTimeEntry(viewModel)
    }
    entry<Route.WebFilters>(metadata = DetailPage()) {
        GateRedirect(pinVerified.value, backStack, PinGateTarget.WebFilters)
        if (pinVerified.value) WebContentFiltersScreen()
    }
}

/** The verified daily-limit editor. */
@Composable
private fun DailyLimitEntry(viewModel: SupervisionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DailyLimitScreen(
        currentMinutes = state.dailyLimitMinutes,
        actions = DailyLimitActions(onLimitChange = viewModel::setDeviceDailyLimit),
    )
}

/** The verified per-app limits editor. */
@Composable
private fun AppLimitsEntry(viewModel: SupervisionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppLimitsScreen(
        state = state,
        actions = AppLimitsActions(onLimitChange = viewModel::setDailyLimit),
    )
}

/** The verified bedtime editor. */
@Composable
private fun BedtimeEntry(viewModel: SupervisionViewModel) {
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

/** The verified downtime schedule editor. */
@Composable
private fun DowntimeEntry(viewModel: SupervisionViewModel) {
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

/** The verified school-time schedule editor. */
@Composable
private fun SchoolTimeEntry(viewModel: SupervisionViewModel) {
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

/** The PIN-setup destination; completion hands over to the pending gate. */
private fun EntryProviderScope<Route>.pinSetupEntry(
    pin: ParentPin,
    scope: CoroutineScope,
    backStack: NavBackStack<Route>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    onFinish: () -> Unit,
) {
    entry<Route.PinSetup>(metadata = DetailPage()) {
        PinSetupScreen(
            actions = PinSetupActions(
                onSet = { entry ->
                    val after = pendingAfterSetup.value
                    pendingAfterSetup.value = null
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
                onCancel = { leaveGate(backStack, onFinish) },
            ),
        )
    }
}

/** The PIN-gate destination; verification applies the pending gated action. */
private fun EntryProviderScope<Route>.pinGateEntry(
    viewModel: SupervisionViewModel,
    pin: ParentPin,
    scope: CoroutineScope,
    backStack: NavBackStack<Route>,
    pinVerified: MutableState<Boolean>,
    pendingControls: MutableState<Boolean?>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    onFinish: () -> Unit,
) {
    entry<Route.PinGate>(metadata = DetailPage()) { route ->
        var error by remember(route) { mutableStateOf<String?>(null) }
        var lockedOut by remember(route) { mutableStateOf(pin.isLockedOut()) }
        val wrongPin = stringResource(R.string.pin_wrong)
        val lockedOutText = stringResource(R.string.pin_locked_out)
        DisposableEffect(route) {
            onDispose {
                // Leaving without verifying (cancel, system back) drops the
                // pending gated action; a later gate must not apply it.
                if (!pinVerified.value) pendingControls.value = null
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
                            pinVerified.value = true
                            when (route.target) {
                                PinGateTarget.Controls -> {
                                    pendingControls.value?.let { viewModel.setControlsEnabled(it) }
                                    pendingControls.value = null
                                    if (backStack.backStack.size <= 1) {
                                        backStack.reset(Route.Dashboard)
                                    } else {
                                        backStack.pop()
                                    }
                                }
                                PinGateTarget.ManagePin -> {
                                    pendingAfterSetup.value = null
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
                onCancel = { leaveGate(backStack, onFinish) },
            ),
        )
    }
}
