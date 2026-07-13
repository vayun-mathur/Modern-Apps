package com.vayunmathur.camera

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.vayunmathur.camera.ui.CameraScreen
import com.vayunmathur.camera.ui.SettingsPage
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Camera : Route
    @Serializable
    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Honor the system "take a photo" intent (MediaStore.ACTION_IMAGE_CAPTURE) so other apps
        // can request a photo via startActivityForResult. Default to CANCELED so back/cancel
        // returns the right result to the caller.
        val captureForResult = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE
        if (captureForResult) {
            setResult(RESULT_CANCELED)
            val outputUri = IntentCompat.getParcelableExtra(
                intent, MediaStore.EXTRA_OUTPUT, Uri::class.java
            )
            viewModel.enableCaptureForResult(outputUri)
        }

        setContent {
            DynamicTheme {
                PermissionsChecker(
                    permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    text = getString(R.string.grant_camera_permission)
                ) {
                    val backStack = rememberNavBackStack<Route>(Route.Camera)
                    MainNavigation(backStack) {
                        entry<Route.Camera> {
                            CameraScreen(
                                backStack,
                                viewModel,
                                onCaptureResult = if (captureForResult) ::finishWithCaptureResult else null
                            )
                        }
                        entry<Route.Settings>(metadata = DialogPage()) {
                            SettingsPage(backStack, viewModel)
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the IMAGE_CAPTURE result to the caller. When [thumbnail] is null the full-res JPEG
     * was already written to the caller's EXTRA_OUTPUT Uri; otherwise it is returned under "data".
     */
    private fun finishWithCaptureResult(thumbnail: Bitmap?) {
        val data = thumbnail?.let { Intent().putExtra("data", it) }
        setResult(RESULT_OK, data)
        finish()
    }

    // Volume keys act as a hardware shutter: routed to the same capture action as the on-screen
    // button (the CameraScreen collects shutterEvents and picks the right action for the mode).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            viewModel.triggerShutter()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    // Consume the matching key-up so the system volume UI doesn't appear.
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }
}
