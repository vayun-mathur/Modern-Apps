package com.vayunmathur.findfamily
import com.vayunmathur.findfamily.ui.MissingFeaturesDialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.findfamily.domain.AddInvite
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.platform.Platform
import com.vayunmathur.findfamily.ui.FirstRunSheet
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.PersonSheet
import com.vayunmathur.findfamily.ui.UwbRangingScreen
import com.vayunmathur.findfamily.ui.WaypointSheet
import com.vayunmathur.findfamily.ui.dialogs.AddLinkDialog
import com.vayunmathur.findfamily.ui.dialogs.AddPersonDialog
import com.vayunmathur.findfamily.ui.dialogs.AddTrackerDialog
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun Navigation(
    platform: Platform,
    ffViewModel: FindFamilyViewModel,
    showMissingFeatures: Boolean,
    deepLinkUwbPeerId: Long? = null,
    deepLinkAddInvite: AddInvite? = null,
    onDeepLinkAddConsumed: () -> Unit = {},
    noShowTarget: Pair<Long, Long?>? = null,
    onNoShowTargetConsumed: () -> Unit = {},
    showFirstRun: Boolean = false,
    onFirstRunFinished: () -> Unit = {},
) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage)

    // Staged first-run sheet, first-run users only. The effect keys on
    // showFirstRun only, so dismissing the sheet ("Later") does not re-push
    // it in the same launch; it returns on the next launch until finished.
    LaunchedEffect(showFirstRun) {
        if (showFirstRun && backStack.backStack.lastOrNull() !is Route.FirstRunSheet) {
            backStack.add(Route.FirstRunSheet)
        }
    }

    LaunchedEffect(showMissingFeatures) {
        if (showMissingFeatures) {
            backStack.add(Route.MissingFeaturesDialog)
        }
    }

    LaunchedEffect(deepLinkUwbPeerId) {
        if (deepLinkUwbPeerId != null) {
            backStack.add(Route.UwbRangingPage(deepLinkUwbPeerId))
        }
    }

    // A tapped invite prefills the Add Person dialog; the user still explicitly
    // accepts, so the connection/crypto flow is unchanged.
    LaunchedEffect(deepLinkAddInvite) {
        if (deepLinkAddInvite != null) {
            backStack.add(Route.AddPersonDialog(deepLinkAddInvite.id, deepLinkAddInvite.fingerprint))
            onDeepLinkAddConsumed()
        }
    }

    // A tapped no-show notification opens the watched person's sheet over
    // the map. The expected place rides along in the alert itself; selection
    // holds one destination, and the person is what the sheet showed first
    // before this refactor too.
    LaunchedEffect(noShowTarget) {
        if (noShowTarget != null) {
            backStack.add(Route.PersonSheet(noShowTarget.first))
            onNoShowTargetConsumed()
        }
    }

    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(platform, backStack, ffViewModel)
        }
        entry<Route.PersonSheet>(metadata = DialogPage()) {
            PersonSheet(backStack, ffViewModel, platform, it.userId)
        }
        entry<Route.WaypointSheet>(metadata = DialogPage()) {
            WaypointSheet(backStack, ffViewModel, it.waypointId)
        }
        entry<Route.UserPageHistoryDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(
                backStack,
                "HistoryDatePicker",
                it.initialDate,
                maxDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            )
        }
        entry<Route.AddPersonDialog>(metadata = DialogPage()) {
            AddPersonDialog(backStack, ffViewModel, platform, it.id, it.fingerprint)
        }
        entry<Route.AddLinkDialog>(metadata = DialogPage()) {
            AddLinkDialog(backStack, ffViewModel)
        }
        entry<Route.AddTrackerDialog>(metadata = DialogPage()) {
            AddTrackerDialog(backStack)
        }
        entry<Route.MissingFeaturesDialog>(metadata = DialogPage()) {
            MissingFeaturesDialog(backStack)
        }
        entry<Route.FirstRunSheet>(metadata = DialogPage()) {
            FirstRunSheet(backStack, ffViewModel, onFinished = onFirstRunFinished)
        }
        entry<Route.UwbRangingPage> {
            UwbRangingScreen(backStack, ffViewModel, it.userId)
        }
    }
}
