package com.vayunmathur.share

import com.vayunmathur.share.ui.SharePopupActivity
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.share.platform.SharePermissions
import com.vayunmathur.share.platform.ShareShortcuts
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareViewModelFactory

/**
 * The app's own screen, reached from the launcher or by long-pressing the Quick Settings tile.
 *
 * Share-sheet intents do NOT land here: [SharePopupActivity] owns ACTION_SEND / SEND_MULTIPLE so
 * that picking Share from another app opens a bottom sheet instead of the whole app.
 */
class MainActivity : MaActivity() {
    private val shareViewModel: ShareViewModel by viewModels { ShareViewModelFactory(application) }

    override fun onCreateBeforeContent() {
        // Direct Share row entry; refreshed on every app open (boot republishes too).
        ShareShortcuts.publish(this)
    }

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.share_permission_rationale),
                requirements = listOf(
                    PermissionRequirement.Runtime(SharePermissions.allSharePermissions())
                )
            )
        ) { Navigation(shareViewModel) }
    }
}
