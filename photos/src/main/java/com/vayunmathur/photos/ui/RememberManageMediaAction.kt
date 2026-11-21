package com.vayunmathur.photos.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.library.ui.SpecialAccess

/**
 * Wraps an action that edits shared media (trash, delete, album move) so
 * MANAGE_MEDIA is requested contextually instead of at app start.
 *
 * Returns a function to call in place of the action: when media management
 * is already granted the action runs immediately; otherwise the action is
 * held while the system "Allow media management" settings page opens (the
 * same intent the gallery's old helper built). That page returns no reliable
 * result code, so on return the grant is re-checked: granted runs the held
 * action, still denied drops it and reports through [onDenied], where the
 * caller explains why the action could not proceed. Browsing never passes
 * through here, so denying the grant costs nothing until an edit is tried.
 */
@Composable
fun rememberManageMediaAction(onDenied: () -> Unit): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    val gate = remember { ManageMediaGate() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        gate.onRequestReturned()
    }
    SideEffect {
        gate.isGranted = { SpecialAccess.hasManageMedia(context) }
        gate.launchRequest = {
            try {
                launcher.launch(
                    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            } catch (_: ActivityNotFoundException) {
                // No settings page can ask: treat as a denied return.
                gate.onRequestReturned()
            }
        }
        gate.onDenied = { currentOnDenied() }
    }
    return remember(gate) { { action -> gate.run(action) } }
}

/**
 * The grant/pending state machine behind [rememberManageMediaAction],
 * separated from Compose so the defer-and-recheck behaviour is unit-testable
 * (the [FavoriteRequestDriver] pattern).
 */
internal class ManageMediaGate(
    var isGranted: () -> Boolean = { false },
    var launchRequest: () -> Unit = {},
    var onDenied: () -> Unit = {},
) {
    private var pending: (() -> Unit)? = null

    /** Run [action] now if granted; otherwise hold it and ask for the grant. */
    fun run(action: () -> Unit) {
        if (isGranted()) {
            pending = null
            action()
        } else {
            pending = action
            launchRequest()
        }
    }

    /** The settings trip ended: run the held action if granted, else deny it. */
    fun onRequestReturned() {
        val action = pending ?: return
        pending = null
        if (isGranted()) action() else onDenied()
    }
}
