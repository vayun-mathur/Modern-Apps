package com.vayunmathur.astronomy.ui
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.SkyMapActions
import com.vayunmathur.astronomy.platform.SkyMapUiState
import com.vayunmathur.astronomy.platform.resolveViewCenter
import com.vayunmathur.astronomy.platform.resolveViewRotation
import com.vayunmathur.astronomy.ui.components.CameraBackground
import com.vayunmathur.library.ui.CompassCalibrationBanner
import com.vayunmathur.library.ui.HistoryScrubberCard
import com.vayunmathur.library.ui.HistoryStep
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconCameraOff
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.rememberHistoryScrubberState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.ExperimentalTime

/**
 * The sky map, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun SkyMapScreen(
    backStack: NavBackStack<Route>,
    state: SkyMapUiState,
    actions: SkyMapActions,
    /**
     * Seed for the screen's own UI-only state (whether the camera AR overlay is up). The
     * app always takes the default; a preview can set it to capture that mode directly.
     */
    initialCameraOn: Boolean = false,
) {
    var screenW by remember { mutableFloatStateOf(1080f) }
    var screenH by remember { mutableFloatStateOf(1920f) }
    // Camera feed replaces the sky background in place (AR overlay) instead of a
    // separate page.
    var cameraOn by remember { mutableStateOf(initialCameraOn) }

    val viewState = remember(state.centerAzRad, state.centerAltRad, state.rotationRad, state.fovDeg, screenW, screenH) {
        ViewState(state.centerAzRad, state.centerAltRad, state.fovDeg, screenW, screenH, state.rotationRad)
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { sz -> screenW = sz.width.toFloat(); screenH = sz.height.toFloat() }
    ) {
        if (cameraOn) {
            CameraBackground(Modifier.fillMaxSize()) { cameraOn = false }
        }

        SkyCanvas(
            visibleSky = state.visibleSky,
            viewState = viewState,
            showConstellationLines = state.constellationMode != ConstellationMode.OFF,
            showConstellationArt = state.constellationMode == ConstellationMode.LINES_AND_ART,
            showGrid = state.showGrid,
            showDeepSky = state.showDeepSky,
            showPlanets = state.showPlanets,
            transparentBackground = cameraOn,
            trajectory = state.trajectory,
            selectedId = state.selectedObjectId,
            onPan = { _, _ -> /* disabled – always tracks phone */ },
            onZoom = { actions.setFov(it) },
            onTap = { _ -> },
            onObjectTap = { id -> actions.selectObject(id) },
            onObjectOpen = { id -> actions.selectObject(id); backStack.add(Route.ObjectDetail(id)) },
            modifier = Modifier.fillMaxSize()
        )

        HistoryScrubber(backStack, state, actions)

        if (state.nightMode) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x44FF0000)))
        }

        CompassCalibrationBanner(
            accuracy = state.accuracy,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 16.dp, end = 16.dp),
        )

        TopAppBarOverlay(
            modifier = Modifier.align(Alignment.TopCenter),
            actions = listOf(
                OverlayAction(
                    icon = { IconSearch() },
                    contentDescription = stringResource(R.string.search),
                    onClick = { backStack.add(Route.Search) },
                ),
                OverlayAction(
                    icon = { if (cameraOn) IconCameraOff() else IconCamera() },
                    contentDescription = stringResource(R.string.camera),
                    onClick = { cameraOn = !cameraOn },
                ),
                OverlayAction(
                    icon = { IconSettings() },
                    contentDescription = stringResource(R.string.settings),
                    onClick = { backStack.add(Route.Settings) },
                ),
            ),
        )
    }
}
