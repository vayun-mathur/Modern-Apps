package com.vayunmathur.photos.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.platform.FavoriteToggler

/**
 * Wires a [FavoriteToggler] to an activity-result launcher and returns the
 * function that toggles a set of photos (#912) — the viewer's heart passes
 * one photo, the grids pass their selection.
 *
 * Each MediaStore request is launched like the trash request: silently
 * applied when the app holds "manage media", otherwise behind the system
 * dialog. A request MediaStore will not build shows a message. [onApplied]
 * runs once everything asked for is applied (the grids clear their selection
 * there); a declined dialog leaves the selection as it was.
 */
@Composable
fun rememberFavoriteToggle(toggler: FavoriteToggler, onApplied: () -> Unit = {}): (List<Photo>) -> Unit {
    val messenger = rememberMessenger()
    val failedMessage = stringResource(R.string.favorite_failed)
    val currentOnApplied by rememberUpdatedState(onApplied)
    val driver = remember(toggler) { FavoriteRequestDriver(toggler) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        driver.handle(toggler.onResult(result.resultCode == Activity.RESULT_OK))
    }
    SideEffect {
        driver.launcher = launcher
        driver.onFailed = { messenger.show(failedMessage) }
        driver.onApplied = { currentOnApplied() }
    }
    return remember(driver) { { photos -> driver.handle(toggler.toggle(photos)) } }
}

/** Turns each [FavoriteToggler.Step] into a launch, a message or a callback. */
private class FavoriteRequestDriver(private val toggler: FavoriteToggler) {
    var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    var onFailed: () -> Unit = {}
    var onApplied: () -> Unit = {}

    fun handle(step: FavoriteToggler.Step) {
        when (step) {
            is FavoriteToggler.Step.Launch -> launch(step)
            FavoriteToggler.Step.Done -> onApplied()
            FavoriteToggler.Step.Failed -> onFailed()
            FavoriteToggler.Step.Cancelled, FavoriteToggler.Step.Busy -> Unit
        }
    }

    private fun launch(step: FavoriteToggler.Step.Launch) {
        val target = launcher
        if (target == null) {
            toggler.onResult(granted = false)
            return
        }
        try {
            target.launch(IntentSenderRequest.Builder(step.intent.intentSender).build())
        } catch (_: ActivityNotFoundException) {
            // Nothing can show the request: undo the optimistic change.
            toggler.onResult(granted = false)
            onFailed()
        }
    }
}
