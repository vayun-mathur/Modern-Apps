package com.vayunmathur.appstore.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.util.UpdateCheckStatus
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.SettingsRow

/**
 * Background update-check health on the settings screen.
 *
 * Updates only arrive when the periodic worker actually runs, and the OS can
 * silently stop it — battery restriction, or GrapheneOS hibernation after long
 * disuse — so this row reports what is true rather than what was scheduled:
 * when anything last checked, whether the periodic work is still live, and a
 * warning when the OS reports background work restricted for this app.
 */
@Composable
fun UpdateCheckStatusCard(status: UpdateCheckStatus) {
    val lastCheck = if (status.lastCheckMs > 0L) {
        stringResource(
            R.string.setting_update_check_last,
            DateUtils.getRelativeTimeSpanString(
                status.lastCheckMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ),
        )
    } else {
        stringResource(R.string.setting_update_check_never)
    }
    val schedule = stringResource(
        if (status.scheduled) {
            R.string.setting_update_check_active
        } else {
            R.string.setting_update_check_inactive
        },
    )
    val lines = buildList {
        add(lastCheck)
        add(schedule)
        if (status.backgroundRestricted) {
            add(stringResource(R.string.setting_update_check_restricted))
        }
    }
    Card(Modifier.fillMaxWidth()) {
        SettingsRow(
            title = stringResource(R.string.setting_update_check_title),
            supportingText = lines.joinToString("\n"),
        )
    }
}
