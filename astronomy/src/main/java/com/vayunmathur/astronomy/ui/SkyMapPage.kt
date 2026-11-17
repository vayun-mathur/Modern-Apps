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
import androidx.compose.ui.unit.dp
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

/** Binds [AstronomyViewModel] to the stateless [SkyMapScreen]. */
@OptIn(ExperimentalTime::class)
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
    val viewCenter by viewModel.viewCenter.collectAsState()
    val simTime by viewModel.simTime.collectAsState()
    val isLive by viewModel.isLive.collectAsState()

    val (centerAz, centerAlt) = remember(viewCenter, deviceOrient) {
        resolveViewCenter(viewCenter, deviceOrient)
    }
    val rotation = remember(viewCenter, deviceOrient) { resolveViewRotation(viewCenter, deviceOrient) }

    SkyMapScreen(
        backStack = backStack,
        state = SkyMapUiState(
            visibleSky = visibleSky,
            simTime = simTime,
            timeZone = TimeZone.currentSystemDefault(),
            isLive = isLive,
            fovDeg = fov,
            centerAzRad = centerAz,
            centerAltRad = centerAlt,
            rotationRad = rotation,
            constellationMode = constMode,
            showGrid = showGrid,
            showDeepSky = showDeep,
            showPlanets = showPlanets,
            nightMode = nightMode,
            trajectory = trajectory,
            selectedObjectId = selectedId,
            accuracy = deviceOrient?.accuracy ?: SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
        ),
        actions = viewModel,
    )
}


/**
 * Bottom-center time picker sharing its look/behaviour with FindFamily's history
 * scrubber (see [com.vayunmathur.library.ui.HistoryScrubberCard]). Seeds from the
 * current sim time; while in "Now" mode the sky tracks live, and any interaction
 * switches to the chosen simulated instant.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun BoxScope.HistoryScrubber(
    backStack: NavBackStack<Route>,
    state: SkyMapUiState,
    actions: SkyMapActions,
) {
    val scrubber = rememberHistoryScrubberState(
        initialInstant = state.simTime,
        initialNowMode = state.isLive,
        timeZone = state.timeZone
    )

    LaunchedEffect(scrubber.instant, scrubber.nowMode) {
        actions.setTime(scrubber.instant, live = scrubber.nowMode)
    }

    HistoryScrubberCard(
        state = scrubber,
        steps = AstronomyHistorySteps,
        onDateChipClick = { backStack.add(Route.HistoryDatePicker(scrubber.date)) }
    )

    ResultEffect<LocalDate>("AstroHistoryDatePicker") { scrubber.setDate(it) }
}
