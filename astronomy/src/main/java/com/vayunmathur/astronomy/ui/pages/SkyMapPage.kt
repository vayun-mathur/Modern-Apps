package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.astronomy.ui.ConstellationMode
import com.vayunmathur.astronomy.ui.components.CameraBackground
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlin.time.ExperimentalTime

// Astronomy scrubs across a wide time range, so the relative jumps are hours/days
// rather than the minutes/seconds FindFamily uses.
private val AstronomyHistorySteps = listOf(
    HistoryStep("-1d", -86_400L),
    HistoryStep("-4h", -14_400L),
    HistoryStep("-1h", -3_600L),
    HistoryStep("+1h", 3_600L),
    HistoryStep("+4h", 14_400L),
    HistoryStep("+1d", 86_400L),
)

@Composable
fun SkyMapPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val visibleSky by viewModel.visibleSky.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val constMode by viewModel.constellationMode.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    // removed still mode – always tracking
    val deviceOrient by viewModel.deviceOrientation.collectAsState()
    val trajectory by viewModel.trajectory.collectAsState()
    val selectedId by viewModel.selectedObjectId.collectAsState()

    var screenW by remember { mutableFloatStateOf(1080f) }
    var screenH by remember { mutableFloatStateOf(1920f) }
    // Camera feed replaces the sky background in place (AR overlay) instead of a
    // separate page.
    var cameraOn by remember { mutableStateOf(false) }

    val centerPair = remember(viewModel.viewCenter.collectAsState().value, deviceOrient) {
        viewModel.resolveCenter()
    }
    val (centerAz, centerAlt) = centerPair
    val rotation = remember(viewModel.viewCenter.collectAsState().value, deviceOrient) {
        viewModel.resolveRotation()
    }

    val viewState = remember(centerAz, centerAlt, rotation, fov, screenW, screenH) {
        ViewState(centerAz, centerAlt, fov, screenW, screenH, rotation)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Astronomy") },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Search) }) { IconSearch() }
                    IconButton(onClick = { cameraOn = !cameraOn }) {
                        if (cameraOn) IconCameraOff() else IconCamera()
                    }
                    IconButton(onClick = { backStack.add(Route.Settings) }) { IconSettings() }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize()
                .onSizeChanged { sz -> screenW = sz.width.toFloat(); screenH = sz.height.toFloat() }
        ) {
            if (cameraOn) {
                CameraBackground(Modifier.fillMaxSize()) { cameraOn = false }
            }

            SkyCanvas(
                visibleSky = visibleSky,
                viewState = viewState,
                showConstellationLines = constMode != ConstellationMode.OFF,
                showConstellationArt = constMode == ConstellationMode.LINES_AND_ART,
                showGrid = showGrid,
                showDeepSky = showDeep,
                showPlanets = showPlanets,
                transparentBackground = cameraOn,
                trajectory = trajectory,
                selectedId = selectedId,
                onPan = { _, _ -> /* disabled – always tracks phone */ },
                onZoom = { viewModel.setFov(it) },
                onTap = { _ -> },
                onObjectTap = { id -> viewModel.selectObject(id) },
                onObjectOpen = { id -> viewModel.selectObject(id); backStack.add(Route.ObjectDetail(id)) },
                modifier = Modifier.fillMaxSize()
            )

            HistoryScrubber(backStack, viewModel)

            if (nightMode) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x44FF0000)))
            }
        }
    }
}

/**
 * Bottom-center time picker sharing its look/behaviour with FindFamily's history
 * scrubber (see [com.vayunmathur.library.ui.HistoryScrubberCard]). Seeds from the
 * current sim time; while in "Now" mode the sky tracks live, and any interaction
 * switches to the chosen simulated instant.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun BoxScope.HistoryScrubber(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val state = rememberHistoryScrubberState(
        initialInstant = viewModel.simTime.value,
        initialNowMode = viewModel.isLive.value
    )

    LaunchedEffect(state.instant, state.nowMode) {
        viewModel.setTime(state.instant, live = state.nowMode)
    }

    HistoryScrubberCard(
        state = state,
        steps = AstronomyHistorySteps,
        onDateChipClick = { backStack.add(Route.HistoryDatePicker(state.date)) }
    )

    ResultEffect<LocalDate>("AstroHistoryDatePicker") { state.setDate(it) }
}
