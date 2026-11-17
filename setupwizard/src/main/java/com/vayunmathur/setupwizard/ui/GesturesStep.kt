package com.vayunmathur.setupwizard.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.library.log.Log
import com.vayunmathur.setupwizard.Route
import com.vayunmathur.setupwizard.platform.SetupIntents

@Composable
internal fun GesturesStep(onDone: (Route) -> Unit) {
    val tutorial = remember { SetupIntents.gestureTutorial() }
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_CANCELED) onDone(Route.Gestures)
    }
    GesturesScreen(
        onTryIt = {
            try {
                launcher.launch(tutorial)
            } catch (e: ActivityNotFoundException) {
                Log.status("SetupNavigation", "no gesture tutorial on this image", e)
                onDone(Route.Gestures)
            }
        },
        onSkip = { onDone(Route.Gestures) },
    )
}
