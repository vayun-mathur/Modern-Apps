package com.vayunmathur.speech

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.speech.ui.SetupScreen

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.grant_microphone),
                icon = { IconMic() },
                requirements = listOf(
                    PermissionRequirement.Runtime(
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    )
                )
            )
        ) {
            SetupScreen()
        }
    }
}

