package com.vayunmathur.clock

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.ClockViewModelFactory
import com.vayunmathur.clock.platform.createNotificationChannels
import com.vayunmathur.clock.ui.FullscreenRevokedBanner
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.DataStoreUtils

/**
 * Entry activity that owns the [ClockViewModel], creates notification channels, and gates
 * the UI on notification and alarm permissions.
 */
class MainActivity : MaActivity() {
    private val clockViewModel: ClockViewModel by viewModels {
        ClockViewModelFactory(application, ClockRepository.get(application))
    }
    private lateinit var ds: DataStoreUtils
    private var initialRoute: Route? = null

    override fun onCreateBeforeContent() {
        createNotificationChannels(this)
        ds = DataStoreUtils.getInstance(this)
        initialRoute = clockViewModel.handleIncomingIntent(intent)
    }

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.grant_notifications_permission),
                requirements = listOf(
                    PermissionRequirement.notifications(),
                    PermissionRequirement.ExactAlarms,
                    PermissionRequirement.FullScreenIntent,
                )
            )
        ) {
            Column {
                FullscreenRevokedBanner(modifier = Modifier.fillMaxWidth())
                Navigation(ds, clockViewModel, initialRoute)
            }
        }
    }
}
