package com.vayunmathur.parentalcontrols.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.Route
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.domain.PinGateTarget
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The dashboard destination and the gated destinations it opens. */
internal fun EntryProviderScope<Route>.dashboardEntry(
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
                onOpenBedtime = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.Bedtime)
                },
                onOpenDowntime = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.Downtime)
                },
                onOpenSchoolTime = {
                    requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.SchoolTime)
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
internal fun EntryProviderScope<Route>.gatedEntries(
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
internal fun DailyLimitEntry(viewModel: SupervisionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DailyLimitScreen(
        currentMinutes = state.dailyLimitMinutes,
        actions = DailyLimitActions(onLimitChange = viewModel::setDeviceDailyLimit),
    )
}

/** The verified per-app limits editor. */
@Composable
internal fun AppLimitsEntry(viewModel: SupervisionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppLimitsScreen(
        state = state,
        actions = AppLimitsActions(onLimitChange = viewModel::setDailyLimit),
    )
}

/** The verified bedtime editor. */
@Composable
internal fun BedtimeEntry(viewModel: SupervisionViewModel) {
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
internal fun DowntimeEntry(viewModel: SupervisionViewModel) {
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
internal fun SchoolTimeEntry(viewModel: SupervisionViewModel) {
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
internal fun EntryProviderScope<Route>.pinSetupEntry(
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
internal fun EntryProviderScope<Route>.pinGateEntry(
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
