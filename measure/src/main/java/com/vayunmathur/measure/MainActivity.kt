package com.vayunmathur.measure

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.platform.startSensorsImpl
import com.vayunmathur.measure.platform.stopSensorsImpl

/**
 * Entry activity for Measure: hosts navigation and runs the compass/level sensors only while the app is in the
 * foreground.
 */
class MainActivity : MaActivity() {
    private val viewModel: MeasureViewModel by viewModels()

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }

    // Sensors run only while the app is in front. The compass and level are useless
    // in the background, and the magnetometer is not free to keep polling.
    override fun onStart() {
        super.onStart()
        viewModel.startSensorsImpl()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopSensorsImpl()
    }
}
