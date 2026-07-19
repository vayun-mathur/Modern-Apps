package com.vayunmathur.findfamily.ui

import android.content.Context
import androidx.activity.compose.BackHandler
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
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.rememberBottomSheetScaffoldState
import com.vayunmathur.library.ui.rememberSliderState
import com.vayunmathur.library.room.SqlCipherDbCodec
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toPosition
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.ui.dialogs.SecurityCodeDialog
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.IconVerify
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.formatSpeed
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Peek height with the sheet collapsed — sits a bit higher so more of the
// family list is visible up front while keeping the map usable.
private val SheetPeekHeight = 200.dp
// Compact peek used in history mode: just the contact's name.
private val HistoryPeekHeight = 84.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainPage(
    platform: Platform,
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    initialUserId: Long? = null,
    initialWaypointId: Long? = null
) {
    // Mirror the original `remember(initialUserId)` behaviour: apply the
    // navigation-supplied selection whenever it changes.
    LaunchedEffect(initialUserId, initialWaypointId) {
        ffViewModel.applyInitialSelection(initialUserId, initialWaypointId)
    }

    val selectedUserId by ffViewModel.selectedUserId.collectAsState()
    val selectedWaypointId by ffViewModel.selectedWaypointId.collectAsState()
    val isShowingPresent by ffViewModel.isShowingPresent.collectAsState()
    val historicalPosition by ffViewModel.historicalPosition.collectAsState()
    var showSecurityCode by remember { mutableStateOf(false) }

    val waypointName by ffViewModel.waypointName.collectAsState()
    val waypointRange by ffViewModel.waypointRange.collectAsState()

    // History mode = a contact is selected and we're viewing their past track.
    val historyMode = selectedUserId != null && !isShowingPresent

    BackHandler(selectedUserId != null || (selectedWaypointId != null && selectedWaypointId != 0L)) {
        if (historyMode) {
            ffViewModel.setShowingPresent(true)
        } else {
            ffViewModel.clearSelection()
        }
    }

    val temporaryLinks by ffViewModel.temporaryLinks.collectAsState()
    val waypoints by ffViewModel.waypoints.collectAsState()

    val connectedUsers by ffViewModel.connectedUsers.collectAsState()
    val awaitingRequestUsers by ffViewModel.awaitingRequestUsers.collectAsState()
    val usersByLocationName by ffViewModel.usersByLocationName.collectAsState()
    val userPositions by ffViewModel.latestLocationByUser.collectAsState()

    val scaffoldState = rememberBottomSheetScaffoldState()

    // In history mode drop the sheet entirely (peek 0); the name goes in the app bar.
    val peekHeight = if (historyMode) 0.dp else SheetPeekHeight

    // The FAB sits on top of the always-light map, so color it from a light dynamic
    // scheme regardless of the app's (possibly dark) theme. Captured OUTSIDE the
    // scaffold to avoid the library's in-scaffold color-resolution quirk. Remembered
    // so we don't rebuild the whole palette on every recomposition.
    val context = LocalContext.current
    val lightScheme = remember(context) { dynamicLightColorScheme(context) }
    val fabContainerColor = lightScheme.primaryContainer
    val fabExpandedColor = lightScheme.primary
    val fabContentColor = lightScheme.onPrimaryContainer

    // The sheet's offset when settled at its peek, captured ONCE. Overlays then sit
    // at their default position plus (currentSheetOffset - peekOffset), clamped <= 0,
    // so they move 1:1 with the sheet. Peek is constant (128) whenever overlays show,
    // so a single capture stays correct across list/detail/back-from-history.
    val collapsedSheetOffset = remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(scaffoldState) {
        snapshotFlow {
            val st = scaffoldState.bottomSheetState
            val settled = st.currentValue == SheetValue.PartiallyExpanded &&
                st.targetValue == SheetValue.PartiallyExpanded
            val hist = selectedUserId != null && !isShowingPresent
            if (settled && !hist) runCatching { st.requireOffset() }.getOrNull() else null
        }.collect { off ->
            if (off != null && collapsedSheetOffset.floatValue.isNaN()) {
                collapsedSheetOffset.floatValue = off
            }
        }
    }

    // Leaving history sets the peek back to non-zero, but the collapsed sheet needs
    // a nudge to animate back to its peek. Retry until the anchor is ready.
    LaunchedEffect(historyMode) {
        if (!historyMode) {
            repeat(10) {
                if (runCatching { scaffoldState.bottomSheetState.partialExpand() }.isSuccess) {
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetSwipeEnabled = !historyMode,
        sheetDragHandle = if (historyMode) null else { { BottomSheetDefaults.DragHandle() } },
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Transparent app bar so the map shows through behind it.
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    if (historyMode) {
                        val historyUser by ffViewModel.userByIdState(selectedUserId!!)
                        Text(stringResource(R.string.history_title, historyUser?.name ?: ""))
                    } else if (selectedUserId == null && selectedWaypointId == null) {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    if (selectedUserId != null || selectedWaypointId != null) {
                        IconNavigation {
                            if (historyMode) {
                                ffViewModel.setShowingPresent(true)
                            } else {
                                ffViewModel.clearSelection()
                            }
                        }
                    }
                },
                actions = {
                    if (selectedUserId == null && (selectedWaypointId == null || selectedWaypointId == 0L)) {
                        BackupButtons(
                            dbConfigs = listOf("passwords-db" to ffViewModel.backupPassphrase),
                            dbCodec = SqlCipherDbCodec,
                            extraFiles = emptyList()
                        )
                    } else if (selectedUserId != null && !historyMode) {
                        if (selectedUserId != Networking.userid) {
                            val user by ffViewModel.userByIdState(selectedUserId!!)
                            // UWB Find Nearby (UWB) requires the public android.ranging API
                            // (Android 15+). Hide the entry point on older devices.
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                IconButton({
                                    backStack.add(Route.UwbRangingPage(selectedUserId!!))
                                }) {
                                    IconNavigationArrow()
                                }
                            }
                            IconButton({ showSecurityCode = true }) {
                                IconVerify()
                            }
                            IconButton({
                                user?.let { ffViewModel.deleteUser(it) }
                                ffViewModel.setSelectedUserId(null)
                            }) {
                                IconDelete()
                            }
                        }
                    } else if (selectedWaypointId != null && selectedWaypointId != 0L) {
                        val waypoint by ffViewModel.waypointByIdState(selectedWaypointId!!)
                        IconButton({
                            waypoint?.let { ffViewModel.deleteWaypoint(it) }
                            ffViewModel.setSelectedWaypointId(null)
                        }) {
                            IconDelete()
                        }
                    }
                }
            )
        },
        sheetContent = {
            if (selectedUserId == null && selectedWaypointId == null) {
                LazyColumn(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        connectedUsers,
                        key = { it.id }
                    ) {
                        UserCard(it, userPositions[it.id], true) {
                            ffViewModel.selectUser(it.id)
                        }
                    }
                    if (awaitingRequestUsers.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.section_location_sharing_requests)) }
                    }
                    items(
                        awaitingRequestUsers,
                        key = { it.id }
                    ) {
                        AwaitingRequestCard(backStack, it.id)
                    }
                    if (temporaryLinks.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.section_temporary_links)) }
                    }
                    items(temporaryLinks, key = { it.id }) {
                        TemporaryLinkCard(platform, ffViewModel, it)
                    }
                    if (waypoints.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.section_saved_places)) }
                    }
                    items(waypoints, key = { it.id }) {
                        WaypointCard(it, usersByLocationName[it.name].orEmpty()) {
                            ffViewModel.beginEditWaypoint(it)
                        }
                    }
                }
            } else if (historyMode) {
                // History mode has no sheet; the name is shown in the app bar.
            } else if (selectedUserId != null) {
                val selectedUser by ffViewModel.userByIdState(selectedUserId!!)
                val requestPickContact = platform.requestPickContact { name, photo ->
                    selectedUser?.let { ffViewModel.upsertUser(it.copy(name = name, photo = photo)) }
                }
                selectedUser?.let { user ->
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        UserCard(user, userPositions[user.id], true) {}
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.share_your_location),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                user.sendingEnabled,
                                { send -> ffViewModel.setUserSharing(user, send) }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            { requestPickContact() },
                            Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.change_connected_contact))
                        }
                    }
                }
            } else if (selectedWaypointId != null) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        waypointName,
                        { ffViewModel.setWaypointName(it) },
                        Modifier.fillMaxWidth(),
                        isError = waypointName.isBlank(),
                        supportingText = if (waypointName.isBlank()) {
                            { Text(stringResource(R.string.waypoint_name_blank_error)) }
                        } else null
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        waypointRange,
                        { ffViewModel.setWaypointRange(it) },
                        Modifier.fillMaxWidth(),
                        suffix = { Text(stringResource(R.string.waypoint_range_suffix)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        isError = waypointRange.toDoubleOrNull() == null,
                        supportingText = if (waypointRange.toDoubleOrNull() == null) {
                            { Text(stringResource(R.string.waypoint_range_error)) }
                        } else null
                    )
                }
            }
        }
    ) { _ ->
        // Full-bleed map; overlays (FAB, history bar) sit just above the collapsed
        // sheet peek and lift upward as the sheet expands.
        Box(Modifier.fillMaxSize()) {
            // Lift overlays above their peek baseline as the sheet expands:
            // (current - settledPeekOffset), clamped to <= 0. Uses the settled peek
            // offset (sampled above) so transitions never push overlays off-screen.
            val sheetLiftPx: () -> Int = {
                val base = collapsedSheetOffset.floatValue
                val cur = runCatching { scaffoldState.bottomSheetState.requireOffset() }.getOrNull()
                if (cur != null && !base.isNaN()) (cur - base).roundToInt().coerceAtMost(0) else 0
            }

            val selectedUserObj = if (selectedUserId != null) {
                val user by ffViewModel.userByIdState(selectedUserId!!)
                user?.let { SelectedUser(it, isShowingPresent, historicalPosition) }
            } else null

            val selectedWaypointObj = if (selectedWaypointId != null) {
                val waypoint by ffViewModel.waypointByIdState(selectedWaypointId!!) { Waypoint.NEW_WAYPOINT }
                waypoint?.let { wp -> SelectedWaypoint(wp, waypointRange.toDoubleOrNull() ?: 0.0) {
                    ffViewModel.setWaypointCoord(it)
                } }
            } else null

            MapView(
                ffViewModel,
                onUserClick = {
                    ffViewModel.selectUser(it)
                },
                onMapClick = {
                    ffViewModel.clearSelection()
                },
                selectedUser = selectedUserObj,
                selectedWaypoint = selectedWaypointObj
            )

            if (historyMode) {
                HistoryScrubber(
                    backStack,
                    ffViewModel,
                    selectedUserId!!
                ) { ffViewModel.setHistoricalPosition(it) }
            }

            // FAB floats just above the sheet peek, lifting as the sheet expands.
            // Wrapped in the light scheme so it reads correctly over the light map.
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = peekHeight + 16.dp)
                    .offset { IntOffset(0, sheetLiftPx()) }
            ) {
                MaterialTheme(colorScheme = lightScheme) {
                    if (selectedUserId == null && selectedWaypointId == null) {
                        var expanded by remember { mutableStateOf(false) }
                        FloatingActionButtonMenu(expanded, {
                            ToggleFloatingActionButton(
                                expanded,
                                { expanded = it },
                                containerColor = { progress -> lerp(fabContainerColor, fabExpandedColor, progress) }
                            ) {
                                if (!expanded)
                                    IconAdd(tint = fabContentColor)
                                else
                                    IconClose(tint = fabContentColor)
                            }
                        }) {
                            FloatingActionButtonMenuItem({
                                backStack.add(Route.AddPersonDialog())
                            },
                                { Text(stringResource(R.string.fab_person)) },
                                { IconPerson() })
                            FloatingActionButtonMenuItem({
                                ffViewModel.beginCreateWaypoint()
                            },
                                { Text(stringResource(R.string.fab_location)) },
                                { IconLocationOn() })
                            FloatingActionButtonMenuItem({
                                backStack.add(Route.AddLinkDialog)
                            },
                                { Text(stringResource(R.string.fab_link)) },
                                { IconLink() })
                        }
                    } else if (selectedWaypointId != null) {
                        FloatingActionButton(
                            { ffViewModel.saveCurrentWaypoint() },
                            containerColor = fabContainerColor,
                            contentColor = fabContentColor
                        ) {
                            IconSave()
                        }
                    } else if (selectedUserId != null && isShowingPresent) {
                        // Enter history mode; exit is via back.
                        FloatingActionButton(
                            { ffViewModel.setShowingPresent(false) },
                            containerColor = fabContainerColor,
                            contentColor = fabContentColor
                        ) {
                            IconRestore()
                        }
                    }
                }
            }
        }
    }

    // Map animation logic
    LaunchedEffect(selectedUserId, isShowingPresent, historicalPosition) {
        if (selectedUserId != null) {
            val targetPosition = if (isShowingPresent) {
                userPositions[selectedUserId!!]?.coord?.toPosition()
            } else {
                historicalPosition
            }
            targetPosition?.let {
                camera.animateTo(
                    camera.position.copy(
                        target = it,
                        zoom = 15.0
                    )
                )
            }
        }
    }

    LaunchedEffect(selectedWaypointId) {
        if (selectedWaypointId != null && selectedWaypointId != 0L) {
            val waypoint = waypoints.find { it.id == selectedWaypointId }
            waypoint?.coord?.toPosition()?.let {
                camera.animateTo(
                    camera.position.copy(
                        target = it,
                        zoom = 15.0
                    )
                )
            }
        }
    }

    if (showSecurityCode && selectedUserId != null && selectedUserId != Networking.userid) {
        val user by ffViewModel.userByIdState(selectedUserId!!)
        user?.let { SecurityCodeDialog(it, ffViewModel) { showSecurityCode = false } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmallEmphasized
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.HistoryScrubber(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    userid: Long,
    setHistoricalPosition: (org.maplibre.spatialk.geojson.Position) -> Unit
) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val currentDate = now.date
    val currentTime = now.time
    var pickedLocalDate by remember { mutableStateOf(currentDate) }
    val sliderState = rememberSliderState(
        currentTime.toSecondOfDay().toFloat(), valueRange = 0.0f..(24f * 60f * 60f - 0.1f)
    )

    sliderState.onValueChange = {
        val maximum = if (currentDate == pickedLocalDate) currentTime.toSecondOfDay().toFloat() else null
        if (maximum != null && it > maximum) sliderState.value = maximum
        else sliderState.value = it
    }
    val pickedLocalTime by remember {
        derivedStateOf {
            LocalTime.fromSecondOfDay(sliderState.value.toInt())
        }
    }

    Card(
        Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(sliderState, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    pickedLocalTime.format(DateFormats.TIME_SECOND_AM_PM),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            AssistChip(
                { backStack.add(Route.UserPageHistoryDatePicker(pickedLocalDate)) },
                { Text(pickedLocalDate.format(DateFormats.DATE_INPUT)) }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StepButton(stringResource(R.string.history_step_minus_5m)) { sliderState.value -= 5 * 60 }
                StepButton(stringResource(R.string.history_step_minus_1m)) { sliderState.value -= 60 }
                StepButton(stringResource(R.string.history_step_minus_10s)) { sliderState.value -= 10 }
                StepButton(stringResource(R.string.history_step_plus_10s)) { sliderState.value += 10 }
                StepButton(stringResource(R.string.history_step_plus_1m)) { sliderState.value += 60 }
                StepButton(stringResource(R.string.history_step_plus_5m)) { sliderState.value += 5 * 60 }
            }
        }
    }

    ResultEffect<LocalDate>("HistoryDatePicker") {
        pickedLocalDate = it
    }

    val simulatedTimestamp = pickedLocalDate.atTime(pickedLocalTime)
        .toInstant(TimeZone.currentSystemDefault())

    val locs by ffViewModel.locationHistory.collectAsState()

    LaunchedEffect(simulatedTimestamp, locs) {
        if (locs.isNotEmpty()) {
            val closest = locs.minBy { (it.timestamp - simulatedTimestamp).absoluteValue }
            setHistoricalPosition(closest.coord.toPosition())
        }
    }
}

@Composable
private fun RowScope.StepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick,
        Modifier.weight(1f).heightIn(min = 36.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun AwaitingRequestCard(backStack: NavBackStack<Route>, id: Long) {
    Card {
        ListItem(
            { Text(stringResource(R.string.request_from, id.encodeBase26())) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = {
                IconButton({
                    backStack.add(Route.AddPersonDialog(id))
                }) {
                    IconAdd()
                }
            }
        )
    }
}

@Composable
fun TemporaryLinkCard(platform: Platform, ffViewModel: FindFamilyViewModel, temporaryLink: TemporaryLink) {
    val context = LocalContext.current
    Card {
        ListItem(
            { Text(temporaryLink.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = {
                Text(stringResource(R.string.expires, timestring(temporaryLink.deleteAt, true, context)))
            },
            trailingContent = {
                Row {
                    IconButton({
                        platform.copy("https://findfamily.cc/view/${temporaryLink.id}#key=${temporaryLink.key}")
                    }) {
                        IconCopy()
                    }
                    IconButton({
                        ffViewModel.deleteTemporaryLink(temporaryLink)
                    }) {
                        IconDelete()
                    }
                }
            }
        )
    }
}

@Composable
fun WaypointCard(waypoint: Waypoint, userNamesHere: List<String>, onSelect: () -> Unit) {
    val usersString = when (userNamesHere.size) {
        0 -> stringResource(R.string.nobody_here)
        1 -> stringResource(R.string.user_is_here, userNamesHere.first())
        else -> stringResource(R.string.users_are_here, userNamesHere.joinToString())
    }
    Card(Modifier.clickable(onClick = onSelect)) {
        ListItem(
            content = { Text(waypoint.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = { Text(usersString, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { IconEdit() }
        )
    }
}

@OptIn(ExperimentalTime::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UserCard(user: User, locationValue: LocationValue?, showSupportingContent: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val lastUpdatedTime = locationValue?.let { timestring(it.timestamp, false, context) } ?: stringResource(R.string.last_updated_never)
    val speedString = (locationValue?.speed ?: 0f).formatSpeed()
    val sinceTime = user.lastLocationChangeTime.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeSinceEntry = Clock.System.now() - user.lastLocationChangeTime
    val sinceString = when {
        user.locationName == "Unnamed Location" -> ""
        timeSinceEntry < 60.seconds -> stringResource(R.string.since_just_now)
        timeSinceEntry < 15.minutes -> stringResource(R.string.since_minutes_ago, timeSinceEntry.inWholeMinutes)
        else -> {
            val formattedTime = sinceTime.format(LocalDateTime.Format {
                amPmHour(Padding.NONE)
                chars(":")
                minute()
                chars(" ")
                amPmMarker("am", "pm")
            })
            val formattedDate = when (sinceTime.date.toEpochDays() - Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()) {
                0L -> stringResource(R.string.today)
                1L -> stringResource(R.string.yesterday)
                else -> sinceTime.date.format(DateFormats.MONTH_DAY)
            }
            stringResource(R.string.since_time_date, formattedTime, formattedDate)
        }
    }
    Card(if (showSupportingContent) Modifier.clickable(onClick = onClick) else Modifier) {
        ListItem(
            leadingContent = { UserPicture(user, 40.dp) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = {
                Text(
                    user.name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                if (showSupportingContent) {
                    Text(stringResource(R.string.user_card_status, lastUpdatedTime, user.locationName, sinceString))
                }
            },
            trailingContent = {
                if (showSupportingContent) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(speedString, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(2.dp))
                        locationValue?.battery?.let { BatteryBar(it) }
                    }
                }
            }
        )
    }
}

@Composable
fun BatteryBar(percent: Float, width: Dp = 24.dp, height: Dp = 12.dp) {
    val color = when {
        percent > 50 -> Color.Green
        percent > 20 -> Color.Yellow
        else -> Color.Red
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width, height).border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidthFraction(percent).height(height).background(color, RoundedCornerShape(3.dp)))
        }
        Text(stringResource(R.string.battery_percentage, percent.toInt()), fontSize = 11.sp)
    }
}

private fun Modifier.fillMaxWidthFraction(percent: Float): Modifier =
    this.fillMaxWidth((percent / 100f).coerceIn(0f, 1f))

fun timestring(timestamp: Instant, future: Boolean, context: Context): String {
    val duration = (Clock.System.now() - timestamp).absoluteValue
    return when {
        duration.inWholeSeconds < 60 -> context.getString(if (future) R.string.time_very_soon else R.string.time_just_now)
        duration.inWholeMinutes < 60 -> context.getString(if (future) R.string.time_in_minutes else R.string.time_minutes_ago, duration.inWholeMinutes)
        duration.inWholeHours < 24 -> context.getString(if (future) R.string.time_in_hours else R.string.time_hours_ago, duration.inWholeHours)
        else -> context.getString(if (future) R.string.time_in_days else R.string.time_days_ago, duration.inWholeDays)
    }
}

object DateFormats {
    // example: Jun 4
    val MONTH_DAY = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        chars(" ")
        day()
    }

    // example: 10:05 am
    val TIME_SECOND_AM_PM = LocalTime.Format {
        amPmHour()
        chars(":")
        minute()
        chars(":")
        second()
        chars(" ")
        amPmMarker("AM", "PM")
    }

    val DATE_INPUT = MONTH_DAY
}
