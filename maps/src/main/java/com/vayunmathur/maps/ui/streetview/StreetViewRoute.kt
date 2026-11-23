package com.vayunmathur.maps.ui.streetview

import com.vayunmathur.maps.data.nearest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.data.google.StreetViewPano

/**
 * Route entry for Street View: resolves the nearest pano to [lat]/[lon],
 * then hosts [StreetViewScreen]. No imagery nearby closes straight back.
 */
@Composable
fun StreetViewRoute(lat: Double, lon: Double, onClose: () -> Unit) {
    var pano by remember { mutableStateOf<StreetViewPano?>(null) }
    var resolved by remember { mutableStateOf(false) }
    LaunchedEffect(lat, lon) {
        pano = StreetViewDataSource.nearest(lat, lon)
        resolved = true
        if (pano == null) onClose()
    }
    when {
        pano != null -> StreetViewScreen(initialPano = pano!!, onClose = onClose)
        !resolved -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text("Loading\u2026", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        else -> Unit
    }
}

