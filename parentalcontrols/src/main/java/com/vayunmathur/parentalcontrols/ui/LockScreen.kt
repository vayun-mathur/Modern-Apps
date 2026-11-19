package com.vayunmathur.parentalcontrols.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.platform.Enforcer

/** Actions a restriction lock screen offers. */
data class LockActions(
    val onAskParent: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The full-screen reason shown for a blocked app.
 *
 * One screen serves all four restriction types; [reason] picks the title, explanation and
 * whether bonus time can be requested (daily and per-app limits only - bedtime, downtime and
 * school time end on their own schedule, so extra minutes would be meaningless).
 */
@Composable
fun LockScreen(reason: Enforcer.BlockReason, appLabel: String?, actions: LockActions) {
    val title =
        when (reason) {
            Enforcer.BlockReason.Bedtime -> stringResource(R.string.lock_bedtime_title)
            Enforcer.BlockReason.Downtime -> stringResource(R.string.lock_downtime_title)
            Enforcer.BlockReason.SchoolTime -> stringResource(R.string.lock_school_title)
            Enforcer.BlockReason.DailyLimit -> stringResource(R.string.lock_daily_title)
            Enforcer.BlockReason.AppLimit -> stringResource(R.string.lock_applimit_title)
        }
    val hint =
        when (reason) {
            Enforcer.BlockReason.Bedtime -> stringResource(R.string.lock_bedtime_hint)
            Enforcer.BlockReason.Downtime -> stringResource(R.string.lock_downtime_hint)
            Enforcer.BlockReason.SchoolTime -> stringResource(R.string.lock_school_hint)
            Enforcer.BlockReason.DailyLimit -> stringResource(R.string.lock_daily_hint)
            Enforcer.BlockReason.AppLimit -> stringResource(R.string.lock_applimit_hint)
        }
    val bonusable =
        reason == Enforcer.BlockReason.DailyLimit || reason == Enforcer.BlockReason.AppLimit

    AppScaffold(
        title = title,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (appLabel != null) Text(appLabel)
            Text(hint)
            if (bonusable) {
                TextButton(onClick = actions.onAskParent) {
                    Text(stringResource(R.string.lock_ask_parent))
                }
            }
            TextButton(onClick = actions.onClose) {
                Text(stringResource(R.string.lock_close))
            }
        }
    }
}
