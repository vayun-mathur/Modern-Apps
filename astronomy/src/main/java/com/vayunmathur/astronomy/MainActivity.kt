package com.vayunmathur.astronomy

import android.Manifest
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement

/** Entry activity for the offline sky viewer; requires location permission, then shows navigation. */
class MainActivity : MaActivity() {
    private val viewModel: AstronomyViewModel by viewModels()

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = "Grant location permission — astronomy needs your position for horizon",
                requirements = listOf(
                    PermissionRequirement.Runtime(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                )
            )
        ) {
            Navigation(viewModel)
        }
    }
}
