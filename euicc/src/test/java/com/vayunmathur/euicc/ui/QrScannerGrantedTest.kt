package com.vayunmathur.euicc.ui

import android.Manifest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.Route
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class QrScannerGrantedTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `qr scanner with camera permission shows viewfinder not wall`() {
        Shadows.shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.CAMERA)
        // Pre-initialize CameraX so the composable's awaitInstance resumes
        // from an already-completed future on the composition thread instead
        // of racing CameraX's background initialization thread (which fails
        // PreviewView's main-thread check under Robolectric).
        kotlinx.coroutines.runBlocking {
            ProcessCameraProvider.awaitInstance(ctx)
        }
        val backStack = NavBackStack(arrayOf(Route.Home, Route.ScanQr))
        var result: String? = null
        rule.setContent { QrScannerScreen(backStack = backStack, onResult = { result = it }) }
        // Pump the main looper on this (main) thread so CameraX's resumed
        // continuation lands where PreviewView requires it.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(R.string.camera_permission_required)).assertDoesNotExist()
        rule.onNodeWithText(ctx.getString(R.string.scan_qr_title)).assertExists()
        assertEquals(null, result)
        // Cancel overlay still pops.
        rule.onNodeWithText(ctx.getString(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(Route.Home, backStack.last())
    }
}
