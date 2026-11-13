package com.vayunmathur.games.hub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.platform.GameLauncher
import com.vayunmathur.games.hub.platform.LaunchOutcome
import com.vayunmathur.library.ui.rememberMessenger

/**
 * A `(packageName, displayName) -> Unit` that plays a game, opens its store listing when it is
 * not installed, and - when not even a store is available - says so in a snackbar instead of
 * doing nothing.
 */
@Composable
fun rememberGameOpener(): (packageName: String, displayName: String) -> Unit {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    return remember(context, messenger) {
        { packageName, displayName ->
            if (GameLauncher.launchOrInstall(context, packageName) == LaunchOutcome.UNAVAILABLE) {
                messenger.show(context.getString(R.string.game_unavailable, displayName))
            }
        }
    }
}
