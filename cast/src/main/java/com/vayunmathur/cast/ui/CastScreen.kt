package com.vayunmathur.cast.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.vayunmathur.cast.platform.CastConnection
import com.vayunmathur.cast.platform.CastViewModel

/** Everything the app has to offer, on one screen. */
@Composable
fun CastScreen(viewModel: CastViewModel) {
    val state by viewModel.uiState.collectAsState()
    // Asked for plainly and never insisted on: without it the session still works, it just
    // loses the notification that is the only way to stop mirroring from outside the app.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    // Contextual ask (todo/files pattern), not on first frame: only at the
    // first cast attempt — once a connection is being attempted, the session
    // notification (the way to stop mirroring from outside the app) becomes
    // relevant. Ask once and persist that we asked.
    val context = LocalContext.current
    LaunchedEffect(state.connection) {
        if ((state.connection == CastConnection.Connecting ||
                state.connection == CastConnection.AwaitingCode ||
                state.connection == CastConnection.Connected) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val prefs = context.getSharedPreferences("cast_notifications", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("has_asked_notifications", false)) {
                prefs.edit().putBoolean("has_asked_notifications", true).apply()
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    // The mDNS browse is tied to this screen being on screen: it is the only thing that wants
    // it, and leaving it running once the screen is gone is a radio kept awake for nothing.
    DisposableEffect(viewModel) {
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }
    CastContent(state = state, actions = viewModel)
}
