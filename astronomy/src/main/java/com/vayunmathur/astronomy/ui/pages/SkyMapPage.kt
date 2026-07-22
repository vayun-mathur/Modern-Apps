package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.astronomy.ui.components.CalibrationPrompt
import com.vayunmathur.astronomy.ui.components.CompassOverlay
import com.vayunmathur.astronomy.ui.components.SkyLegend
import com.vayunmathur.astronomy.ui.components.TimeScrubber
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack

@Composable
fun SkyMapPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val visibleSky by viewModel.visibleSky.collectAsState()
    val simTime by viewModel.simTime.collectAsState()
    val isLive by viewModel.isLive.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val showConst by viewModel.showConstellations.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    // removed still mode – always tracking
    val deviceOrient by viewModel.deviceOrientation.collectAsState()
    val trajectory by viewModel.trajectory.collectAsState()
    val selectedId by viewModel.selectedObjectId.collectAsState()
    val observerState by viewModel.observer.collectAsState()

    var screenW by remember { mutableFloatStateOf(1080f) }
    var screenH by remember { mutableFloatStateOf(1920f) }

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
                    IconButton(onClick = { backStack.add(Route.ArSky) }) { IconCamera() }
                    IconButton(onClick = { backStack.add(Route.Settings) }) { IconSettings() }
                }
            )
        },
        bottomBar = {
            Column {
                CompassOverlay(viewState = viewState, deviceOrientation = deviceOrient, modifier = Modifier.fillMaxWidth())
                TimeScrubber(simTime = simTime, isLive = isLive, onTimeChange = { t, live -> viewModel.setTime(t, live) }, modifier = Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize()
                .onSizeChanged { sz -> screenW = sz.width.toFloat(); screenH = sz.height.toFloat() }
        ) {
            SkyCanvas(
                visibleSky = visibleSky,
                viewState = viewState,
                showConstellationLines = showConst,
                showGrid = showGrid,
                showDeepSky = showDeep,
                showPlanets = showPlanets,
                transparentBackground = false,
                trajectory = trajectory,
                selectedId = selectedId,
                onPan = { _, _ -> /* disabled – always tracks phone */ },
                onZoom = { viewModel.setFov(it) },
                onTap = { _ -> },
                onObjectTap = { id -> viewModel.selectObject(id); backStack.add(Route.ObjectDetail(id)) },
                modifier = Modifier.fillMaxSize()
            )

            val obsLocal = observerState
            Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                if (obsLocal == null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text("No location", modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Text("${obsLocal.latDeg.format(2)}°, ${obsLocal.lonDeg.format(2)}°", style = MaterialTheme.typography.labelSmall)
                }
                SkyLegend(visibleSky)
                CalibrationPrompt(orientation = deviceOrient, modifier = Modifier.fillMaxWidth())
            }

            if (nightMode) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x44FF0000)))
            }
        }
    }
}

private fun Double.format(digits: Int): String = "%.${digits}f".format(this)
