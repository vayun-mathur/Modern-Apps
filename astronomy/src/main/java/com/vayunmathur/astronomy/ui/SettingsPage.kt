package com.vayunmathur.astronomy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.astronomy.platform.ConstellationMode
import com.vayunmathur.astronomy.platform.SettingsActions
import com.vayunmathur.astronomy.platform.SettingsUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** Binds [AstronomyViewModel] to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val showConst by viewModel.constellationMode.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val showBelow by viewModel.showBelowHorizon.collectAsState()
    val magLimit by viewModel.magLimit.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val observer by viewModel.observer.collectAsState()
    val catalog = viewModel.catalog

    SettingsScreen(
        backStack = backStack,
        state = SettingsUiState(
            constellationMode = showConst,
            showGrid = showGrid,
            showDeepSky = showDeep,
            showPlanets = showPlanets,
            showBelowHorizon = showBelow,
            nightMode = nightMode,
            magLimit = magLimit,
            fovDeg = fov,
            latDeg = observer?.latDeg,
            lonDeg = observer?.lonDeg,
            starCount = catalog.stars.size,
            constellationCount = catalog.constellations.size,
            deepSkyCount = catalog.deepSky.size,
        ),
        actions = viewModel,
    )
}


