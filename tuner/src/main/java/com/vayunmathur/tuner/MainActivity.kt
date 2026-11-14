package com.vayunmathur.tuner

import android.Manifest
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.tuner.platform.TunerViewModel

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val viewModel: TunerViewModel by viewModels()

    @Composable
    override fun Content() {
        // In front of the navigation graph rather than around the tabs: the microphone
        // opens by itself as soon as the tabs compose, so the grant has to be settled
        // before any of the app is on screen.
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.permission_title),
                requirements = listOf(
                    PermissionRequirement.Runtime(
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    )
                )
            )
        ) {
            Navigation(viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        // Pairs with onStop below: the microphone is released while the app is away and reopened
        // on return. No-op until the permission gate lets the content compose.
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        // Nothing here needs to keep listening in the background, and an open microphone is the
        // one part of this app with a real battery cost.
        viewModel.onBackground()
    }
}
