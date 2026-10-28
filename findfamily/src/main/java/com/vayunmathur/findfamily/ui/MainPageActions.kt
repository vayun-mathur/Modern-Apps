package com.vayunmathur.findfamily.ui

import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.is24Hour
import android.Manifest
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.BottomSheetDefaults
import com.vayunmathur.library.ui.BottomSheetScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.FloatingActionButtonMenu
import com.vayunmathur.library.ui.FloatingActionButtonMenuItem
import com.vayunmathur.library.ui.HistoryScrubberCard
import com.vayunmathur.library.ui.HistoryStep
import com.vayunmathur.library.ui.rememberHistoryScrubberState
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.rememberBottomSheetScaffoldState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.rememberSliderState
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.findfamily.ui.dialogs.interactionSourceClickable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.BuildConfig
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toGeoPoint
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.ui.dialogs.GpsFallbackWarningDialog
import com.vayunmathur.findfamily.ui.dialogs.SecurityCodeDialog
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FindFamilyNotificationChannels
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.findfamily.util.UwbSessionManager
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.IconVerify
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.formatSpeed
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
internal fun rememberMainPageActions(
    ffViewModel: FindFamilyViewModel,
    backStack: NavBackStack<Route>,
    platform: Platform,
    selectedUser: User?,
    selectedWaypointId: Long?,
    waypoints: List<Waypoint>,
    onShowGpsWarning: (Boolean) -> Unit,
    onShowSecurityCode: (Boolean) -> Unit,
    requestPickContact: () -> Unit,
): Triple<FamilyListActions, PersonActions, MainPageActions> {
    // The sheets/layout are stateless so the store-listing previews can render them (the
    // map behind them cannot be rendered off-device). The ViewModel supplies the actions
    // it already implements; the ones needing the nav stack or the clipboard go here.
    val familyActions = remember(ffViewModel, backStack, platform) {
        object : FamilyListActions by ffViewModel {
            override fun acceptRequest(userId: Long) {
                backStack.add(Route.AddPersonDialog(userId))
            }

            override fun copyLink(link: TemporaryLink) {
                // The fragment carries the link's secret and never hits the server. New links
                // send just the 32-byte ML-KEM seed (`#s=`) with a Base26 id, which fits in an
                // SMS; links minted before that still carry their full private bundle.
                val seed = link.pqcSeed
                platform.copy(
                    if (seed != null) "${Networking.SHARE_BASE}/${link.id.encodeBase26()}#s=$seed"
                    else "${Networking.SHARE_BASE}/${link.id}#pqc_key=${link.pqcKey}"
                )
            }
        }
    }

    val personActions = object : PersonActions by ffViewModel {
        override fun changeConnectedContact() = requestPickContact()
    }

    val mainActions = object : MainPageActions {
        override fun clearSelection() = ffViewModel.mapState.clearSelection()
        override fun setShowingPresent(present: Boolean) = ffViewModel.mapState.setShowingPresent(present)
        override fun onGpsWarningClick() { onShowGpsWarning(true) }
        override fun onShowSecurityCode() { onShowSecurityCode(true) }
        override fun openUwbRanging(userId: Long) { backStack.add(Route.UwbRangingPage(userId)) }
        override fun deleteSelectedUser() {
            selectedUser?.let { ffViewModel.deleteUser(it) }
            ffViewModel.mapState.setSelectedUserId(null)
        }
        override fun deleteSelectedWaypoint() {
            selectedWaypointId?.let { id -> waypoints.firstOrNull { it.id == id } }
                ?.let { ffViewModel.deleteWaypoint(it) }
            ffViewModel.mapState.setSelectedWaypointId(null)
        }
        override fun addPerson() { backStack.add(Route.AddPersonDialog()) }
        override fun beginCreateWaypoint() = ffViewModel.mapState.beginCreateWaypoint()
        override fun addLink() { backStack.add(Route.AddLinkDialog) }
        override fun addTracker() { backStack.add(Route.AddTrackerDialog) }
        override fun saveCurrentWaypoint() = ffViewModel.mapState.saveCurrentWaypoint()
        override fun enterHistory() = ffViewModel.mapState.setShowingPresent(false)
        override fun setWaypointName(name: String) = ffViewModel.mapState.setWaypointName(name)
        override fun setWaypointRange(range: String) = ffViewModel.mapState.setWaypointRange(range)
    }
    return Triple(familyActions, personActions, mainActions)
}
