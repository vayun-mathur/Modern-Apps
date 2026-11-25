package com.vayunmathur.camera.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.camera.wear.data.WearCameraStore
import com.vayunmathur.camera.wear.sync.WearLinkBridge

/**
 * Camera watch companion entry: a live viewfinder streamed from the phone
 * camera plus remote controls executed on the phone. The watch has no
 * camera of its own — with no phone nearby it says so, honestly.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearCameraStore.get()
        WearLinkBridge.get(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Viewfinder) }
                when (route) {
                    WearRoute.Viewfinder -> WearViewfinderScreen(
                        store = store,
                        onOpenControls = { route = WearRoute.Controls },
                        onOpenLastCapture = { route = WearRoute.LastCapture },
                    )
                    WearRoute.Controls -> WearControlsScreen(
                        store = store,
                        onBack = { route = WearRoute.Viewfinder },
                    )
                    WearRoute.LastCapture -> WearLastCaptureScreen(
                        store = store,
                        onBack = { route = WearRoute.Viewfinder },
                    )
                }
            }
        }
    }
}
