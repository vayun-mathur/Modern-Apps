package com.vayunmathur.camera

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import com.vayunmathur.camera.ui.CameraScreen
import com.vayunmathur.camera.ui.SettingsPage
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the camera app. */
sealed interface Route : NavKey {
    @Serializable
    /** The main camera capture screen. */
    data object Camera : Route
    @Serializable
    /** Camera settings screen. */
    data object Settings : Route
}

/** Entry activity hosting the camera and settings screens. */
class MainActivity : MaActivity() {
    private val viewModel: CameraViewModel by viewModels()
    private var captureForResult: Boolean = false

    override fun applyEdgeToEdge() {
        // Force a black three-button navigation bar even in light mode (the
        // camera UI is always dark), instead of the default light/transparent
        // scrim a bare enableEdgeToEdge() gives in light mode.
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
    }

    override fun onCreateBeforeContent() {
        // Honor the system "take a photo" intent (MediaStore.ACTION_IMAGE_CAPTURE) so other apps
        // can request a photo via startActivityForResult. Default to CANCELED so back/cancel
        // returns the right result to the caller.
        captureForResult = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE
        if (captureForResult) {
            setResult(RESULT_CANCELED)
            val outputUri = IntentCompat.getParcelableExtra(
                intent, MediaStore.EXTRA_OUTPUT, Uri::class.java
            )
            viewModel.enableCaptureForResult(outputUri)
        }
    }

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = getString(R.string.grant_camera_permission),
                requirements = listOf(
                    PermissionRequirement.Runtime(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                )
            )
        ) {
            val backStack = rememberNavBackStack<Route>(Route.Camera)
            // Land on settings when opened from the system App Info page.
            backStack.OpenSettingsIfRequested(Route.Settings)
            MainNavigation(backStack) {
                entry<Route.Camera>(metadata = FullscreenPage()) {
                    CameraScreen(
                        backStack,
                        viewModel,
                        onCaptureResult = if (captureForResult) ::finishWithCaptureResult else null
                    )
                }
                entry<Route.Settings> {
                    SettingsPage(backStack, viewModel)
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
    // Desktop keyboards cannot send volume keys, so Esc/space-preview also triggers the shutter
    // without disturbing phones (phones rarely carry a hardware keyboard; onKeyDown still wins).
    // Gated on the camera route being foreground so keys pressed in Settings don't take a
    // photo behind your back (and volume keeps adjusting volume there).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (isShutterKey(keyCode) && viewModel.foregroundRoute == "camera") {
            viewModel.triggerShutter()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    // Consume the matching key-up so the system volume UI doesn't appear (camera route only).
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (isVolumeKey(keyCode) && viewModel.foregroundRoute == "camera") {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    private fun isShutterKey(keyCode: Int): Boolean =
        isVolumeKey(keyCode) ||
            keyCode == KeyEvent.KEYCODE_SPACE ||
            keyCode == KeyEvent.KEYCODE_ESCAPE
}
