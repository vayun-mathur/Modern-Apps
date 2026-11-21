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
import com.vayunmathur.findfamily.ui.dialogs.interactionSourceClickable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.vayunmathur.findfamily.platform.FamilyListActions
import com.vayunmathur.findfamily.notifications.FindFamilyNotificationChannels
import com.vayunmathur.findfamily.platform.FamilyListUiState
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.platform.MainPageActions
import com.vayunmathur.findfamily.platform.MainPageUiState
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.platform.PersonActions
import com.vayunmathur.findfamily.platform.PersonUiState
import com.vayunmathur.findfamily.platform.Platform
import com.vayunmathur.findfamily.platform.UwbSessionManager
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.CameraPosition
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

// Peek height with the sheet collapsed — sits a bit higher so more of the
// family list is visible up front while keeping the map usable.
/**
 * How much taller than the peek the sheet content is forced to be.
 *
 * BottomSheetScaffold anchors PartiallyExpanded at (container - peek) and Expanded at
 * (container - sheetHeight). If the content is no taller than the peek those coincide, Material3
 * keeps only Expanded, and the state - still targeting PartiallyExpanded - throws
 * AnchoredDraggableUninitializedException from the measure pass. Any positive margin avoids that;
 * this one is large enough to survive a rounding difference at an unusual density.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainPage(
    platform: Platform,
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
) {
    // Selection lives in the back stack: the top destination (if it is a
    // sheet) supplies the ids the map/camera react to. This page itself
    // always shows the family list; the sheet destinations host the
    // person/waypoint UI.
    val topRoute = backStack.backStack.lastOrNull()
    val selectedUserId = (topRoute as? Route.PersonSheet)?.userId
    val selectedWaypointId = (topRoute as? Route.WaypointSheet)?.waypointId
    val isShowingPresent by ffViewModel.isShowingPresent.collectAsState()
    val historicalPosition by ffViewModel.historicalPosition.collectAsState()
    var showGpsWarning by remember { mutableStateOf(false) }
    val usingGpsFallback by ffViewModel.usingGpsFallback.collectAsState()

    val waypointRange by ffViewModel.waypointRange.collectAsState()

    // History mode = a contact is selected and we're viewing their past track.
    val historyMode = selectedUserId != null && !isShowingPresent

    // Back from history returns to the person's present location; back
    // anywhere else pops the sheet destination off the stack (NavDisplay).
    BackHandler(selectedUserId != null && historyMode) {
        ffViewModel.mapState.setShowingPresent(true)
    }

    val temporaryLinks by ffViewModel.temporaryLinks.collectAsState()
    val waypoints by ffViewModel.waypoints.collectAsState()
    val globalSharingEnabled by ffViewModel.globalSharingEnabled.collectAsState()
    val crowdFindingEnabled by ffViewModel.crowdFindingEnabled.collectAsState()

    val connectedUsers by ffViewModel.connectedUsers.collectAsState()
    val awaitingRequestUsers by ffViewModel.awaitingRequestUsers.collectAsState()
    val usersByLocationName by ffViewModel.usersByLocationName.collectAsState()
    val userPositions by ffViewModel.latestLocationByUser.collectAsState()

    val relayConnected by ffViewModel.relayConnected.collectAsState()

    val context = LocalContext.current

    // The selected contact (from the sheet route on top of the stack),
    // resolved from the full user list. Drives the map highlight.
    val users by ffViewModel.users.collectAsState()
    val selectedUser = selectedUserId?.let { id -> users.firstOrNull { it.id == id } }

    val (familyActions, mainActions) = rememberMainPageActions(
        ffViewModel = ffViewModel,
        backStack = backStack,
        platform = platform,
        onShowGpsWarning = { showGpsWarning = it },
    )
    

    val state = MainPageUiState(
        usingGpsFallback = usingGpsFallback,
        relayConnected = relayConnected,
        // Newest report on screen (reportedAt, not the fix's measurement
        // time) — what "last updated" means when the relay drops.
        lastLocationMillis = userPositions.values.maxOfOrNull { it.reportedAt.toEpochMilliseconds() },
        familyList = FamilyListUiState(
            connectedUsers = connectedUsers,
            awaitingRequestUsers = awaitingRequestUsers,
            temporaryLinks = temporaryLinks,
            waypoints = waypoints,
            locationByUser = userPositions,
            userNamesByLocationName = usersByLocationName
        ),
    )

    // Owned here, not by MapView: the two animateTo effects below drive it, and MapView is
    // reached through MainPageContent's stateless `map` slot. This was previously a
    // process-global `val camera` in MapView.kt for exactly that reason. rememberSaveable
    // inside rememberCameraState now also carries it across rotation and process death.
    //
    // Seeded from the device's cached fix (LocationManager.getLastKnownLocation via
    // Platform): synchronous, so cold start opens centred on the user with no DB or
    // fresh-fix wait. Applies only on first composition — a restored camera wins.
    // Read once: the initial expression would otherwise re-query LocationManager on
    // every recomposition.
    val seedTarget = remember { platform.lastKnownDeviceLocation() }
    val camera = rememberCameraState(
        initial = seedTarget?.let { CameraPosition(target = it, zoom = 15.0) }
            ?: CameraPosition()
    )

    MainPageContent(
        state = state,
        familyActions = familyActions,
        actions = mainActions,
        backupPassphrase = ffViewModel.backupPassphrase,
        map = {
            MainMapSlot(
                ffViewModel = ffViewModel,
                camera = camera,
                selectedUserId = selectedUserId,
                selectedUser = selectedUser,
                isShowingPresent = isShowingPresent,
                historicalPosition = historicalPosition,
                selectedWaypointId = selectedWaypointId,
                waypointRange = waypointRange,
                onUserClick = { userId ->
                    ffViewModel.mapState.onPersonOpened()
                    backStack.add(Route.PersonSheet(userId))
                },
                onMapClick = {
                    // Tapping the map away from any marker dismisses an open
                    // sheet (normally unreachable under the sheet's scrim).
                    if (topRoute is Route.PersonSheet || topRoute is Route.WaypointSheet) {
                        backStack.pop()
                    }
                },
            )
            
        }
    )

    MainPageEffects(
        camera = camera,
        selectedUserId = selectedUserId,
        selfUserId = Networking.userid,
        isShowingPresent = isShowingPresent,
        historicalPosition = historicalPosition,
        userPositions = userPositions,
        selectedWaypointId = selectedWaypointId,
        waypoints = waypoints,
        showGpsWarning = showGpsWarning,
        onShowGpsWarning = { showGpsWarning = it },
    )

}
