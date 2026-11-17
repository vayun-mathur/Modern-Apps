package com.vayunmathur.library.util

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Opens the app's own settings when the system launched it to do exactly that.
 *
 * Android's App Info screen shows an entry pointing back into an app's
 * settings, but only if the app advertises one with an
 * [Intent.ACTION_APPLICATION_PREFERENCES] filter. Declaring the filter is half
 * the job: without this, tapping that entry drops the user on the home screen
 * instead, which is worse than not offering it at all.
 *
 * Call once from the composable that owns the back stack:
 *
 *     backStack.OpenSettingsIfRequested(Route.Settings)
 *
 * Deliberately a push rather than a stack reset, so back returns to the app's
 * home screen rather than closing it - the user arrived from outside and has
 * nowhere else to go back to.
 */
@Composable
fun <T : NavKey> NavBackStack<T>.OpenSettingsIfRequested(settings: T) {
    val activity = LocalActivity.current
    LaunchedEffect(activity) {
        val launchedFromAppInfo =
            activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES
        if (launchedFromAppInfo && last() != settings) {
            add(settings)
            // The action is consumed: without this, anything that re-reads the
            // intent (a configuration change that recreates the activity, or a
            // later relaunch from recents) would jump to settings again.
            activity.intent.action = Intent.ACTION_MAIN
        }
    }
}
