package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack

@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val showConst by viewModel.showConstellations.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val showBelow by viewModel.showBelowHorizon.collectAsState()
    val magLimit by viewModel.magLimit.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val observer by viewModel.observer.collectAsState()

    var latText by remember(observer) { mutableStateOf(observer?.latDeg?.toString() ?: "") }
    var lonText by remember(observer) { mutableStateOf(observer?.lonDeg?.toString() ?: "") }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconNavigation(backStack) })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Display", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Constellation lines"); Switch(checked = showConst, onCheckedChange = { viewModel.setShowConstellations(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Coordinate grid (whole sphere)"); Switch(checked = showGrid, onCheckedChange = { viewModel.setShowGrid(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Deep sky (Messier)"); Switch(checked = showDeep, onCheckedChange = { viewModel.setShowDeepSky(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Planets / Sun / Moon"); Switch(checked = showPlanets, onCheckedChange = { viewModel.setShowPlanets(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Show below horizon (all sky)"); Switch(checked = showBelow, onCheckedChange = { viewModel.setShowBelowHorizon(it) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Night mode (red)"); Switch(checked = nightMode, onCheckedChange = { viewModel.setNightMode(it) }) }

            HorizontalDivider()

            Text("Magnitude limit: ${magLimit.format(1)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = magLimit, onValueChange = { viewModel.setMagLimit(it) }, valueRange = 1f..7f)

            Text("FOV: ${fov.toInt()}°", style = MaterialTheme.typography.bodyMedium)
            Slider(value = fov, onValueChange = { viewModel.setFov(it) }, valueRange = 10f..120f)

            HorizontalDivider()
            Text("Location", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text("Latitude deg") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = lonText, onValueChange = { lonText = it }, label = { Text("Longitude deg") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val lat = latText.toDoubleOrNull(); val lon = lonText.toDoubleOrNull()
                if (lat != null && lon != null) viewModel.setManualLocation(lat, lon)
            }) { Text("Save location") }
            Button(onClick = { viewModel.refreshLocation() }) { Text("Use current location") }

            HorizontalDivider()
            Text("Notes: True north correction via geomagnetic declination. AR uses CameraX + rotation vector fusion.", style = MaterialTheme.typography.labelSmall)
            Text("Catalog: ${viewModel.getCatalog().stars.size} stars, ${viewModel.getCatalog().constellations.size} constellations, ${viewModel.getCatalog().deepSky.size} DSO.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Float.format(d: Int): String = "%.${d}f".format(this)
