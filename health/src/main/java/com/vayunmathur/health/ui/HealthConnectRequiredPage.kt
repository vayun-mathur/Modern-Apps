package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.health.platform.HealthConnectAvailability
import com.vayunmathur.library.ui.IconHealth
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * The blocking setup step shown when Health Connect is missing or too old to use.
 *
 * On Android 14 and later Health Connect is part of the OS, but on 13 and below it is a
 * separate APK the user must install — and `HealthConnectClient.getOrCreate` throws when it is
 * absent, which crashed the app on launch before any permission screen. So this renders instead
 * of the whole app (permissions gate included) until the provider is usable, explains what to
 * install, and sends the user to the Play Store listing. Returning from the store re-checks
 * automatically via the resume probe in `MainActivity`; nothing here needs a result callback.
 *
 * [onInstall] opens the store listing and reports whether anything handled it. When nothing
 * does — no store, no browser — the failure stays on this screen as text rather than a toast,
 * since there is no navigation host behind it to carry a snackbar.
 */
@Composable
fun HealthConnectRequiredPage(
    availability: HealthConnectAvailability,
    onInstall: () -> Boolean,
) {
    var installFailed by rememberSaveable { mutableStateOf(false) }
    val updating = availability == HealthConnectAvailability.UPDATE_REQUIRED
    SetupScaffold(
        title = stringResource(
            if (updating) R.string.health_connect_update_title
            else R.string.health_connect_required_title
        ),
        subtitle = stringResource(
            if (updating) R.string.health_connect_update_subtitle
            else R.string.health_connect_required_subtitle
        ),
        icon = { IconHealth() },
        primaryAction = SetupAction(
            stringResource(
                if (updating) R.string.health_connect_update_action
                else R.string.health_connect_install_action
            ),
            onClick = { installFailed = !onInstall() },
        ),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Text(
            stringResource(
                if (updating) R.string.health_connect_update_body
                else R.string.health_connect_required_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installFailed) {
            Text(
                stringResource(R.string.health_connect_install_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
