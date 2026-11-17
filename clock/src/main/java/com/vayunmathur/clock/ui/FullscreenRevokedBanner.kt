package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.SpecialAccess
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * The permission gate only runs at first launch; the full-screen permission is a
 * toggle the user can revoke at any time afterwards, silently degrading every
 * future alarm to a heads-up. Re-check on every resume and say so in-app,
 * with a one-tap path back to the settings page.
 */
@Composable
fun FullscreenRevokedBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var resumeEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val revoked = remember(resumeEpoch) {
        !SpecialAccess.hasFullScreenIntent(context)
    }
    if (!revoked) return
    Card(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.alarm_fullscreen_revoked)) },
            leadingContent = { IconAlarm() },
            trailingContent = {
                TextButton(onClick = { SpecialAccess.requestFullScreenIntent(context) }) {
                    Text(stringResource(R.string.action_open_settings))
                }
            },
        )
    }
}
