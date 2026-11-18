package com.vayunmathur.camera.ui

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.MainActivity
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Settings-page interactions and the real MainActivity composition. */
@RunWith(AndroidJUnit4::class)
class SettingsInteractionsComposeTest {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun settingsPageChooseFolderAndPostProcessingToggles() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        // Choose-folder launches the system picker intent (shadowed here).
        val choose = rule.onAllNodesWithText("Choose folder", substring = true).fetchSemanticsNodes()
        if (choose.isNotEmpty()) {
            rule.onAllNodesWithText("Choose folder", substring = true)[0].performClick()
            rule.waitForIdle()
        }
        // Toggle every switch (post-processing + mirror + location rows).
        val toggles = rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        assertTrue(toggles >= 3, "expected settings switches, got $toggles")
        for (i in 0 until toggles) {
            rule.onAllNodes(isToggleable())[i].performClick()
            rule.waitForIdle()
        }
    }

    @Test fun settingsColumnSettingVariants() {
        val lens = PhysicalLens(
            logicalCameraId = "0", lensType = LensType.WIDE, facing = LensFacing.BACK,
            focalLengthMm35Eq = 24f, nominalZoomRatio = 1f, fallbackPriority = 0, labelKey = "wide",
        )
        var setting by androidx.compose.runtime.mutableStateOf<CameraSetting?>(CameraSetting.BRIGHTNESS)
        var qr by androidx.compose.runtime.mutableStateOf<String?>("WIFI:T:WPA;S:Home;P:secretpw;;")
        rule.setContent {
            CameraSettingsColumn(
                activeSetting = setting,
                availableLenses = listOf(lens), selectedLens = lens,
                onLensSelected = {}, zoomRatio = 2f,
                availableZoomLevels = listOf("1x" to 1f, "2x" to 2f),
                onZoomSelected = {}, exposureComp = 0.3f, onExposureComp = {},
                shadows = 0.2f, onShadows = {}, warmth = 0.1f, onWarmth = {},
                exposureTimeIndex = 1, onExposureTimeIndex = {},
                blurStrength = 0.6f, onBlurStrength = {},
                manualIsoIndex = 1, isoStops = listOf(100, 200),
                onManualIsoIndex = {}, cameraMode = CameraMode.PORTRAIT,
                onSelectSetting = {},
                qrResult = qr,
                onDismissQr = {},
            )
        }
        for (next in listOf(
            CameraSetting.BRIGHTNESS, CameraSetting.WARMTH, CameraSetting.SHADOWS,
            CameraSetting.PORTRAIT_BLUR, CameraSetting.EXPOSURE_TIME, CameraSetting.ISO,
        )) {
            rule.runOnIdle { setting = next }
            rule.waitForIdle()
            assertTrue(rule.onRoot().fetchSemanticsNode() != null)
        }
        // FIDO QR takes the passkey card branch instead of WiFi.
        rule.runOnIdle { qr = "FIDO:/123456"; setting = null }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
    }
}

/** Test-only accessor: the activity's view model via its delegate field. */
internal fun MainActivity.viewModelForTest(): CameraViewModel {
    val field = MainActivity::class.java.getDeclaredField("viewModel\$delegate")
        .apply { isAccessible = true }
    val lazy = field.get(this) as Lazy<CameraViewModel>
    return lazy.value
}
