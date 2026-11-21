package com.vayunmathur.library.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.util.LocalSnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Transient messages, shown on the snackbar host every app already has.
 *
 * `MainNavigation` provides a [LocalSnackbarHostState] to every screen, and
 * exactly one app was using it. Seven built a second `SnackbarHostState`
 * alongside the provided one, and ten reached for `Toast.makeText` - which
 * ignores the app's theme, renders outside the Material surface, cannot carry
 * an action, and on modern Android may be suppressed entirely when the app is
 * backgrounded.
 *
 * [rememberMessenger] hands back one object that posts to the shared host, so
 * a screen needs neither its own host nor a Context.
 */
class Messenger internal constructor(
    private val scope: CoroutineScope,
    private val host: androidx.compose.material3.SnackbarHostState?,
) {
    /**
     * Show [message], optionally with an action.
     *
     * Silently does nothing when there is no host in scope, which happens only
     * outside [com.vayunmathur.library.util.MainNavigation] - a message is
     * never important enough to crash over.
     */
    fun show(
        message: String,
        actionLabel: String? = null,
        // Long even without an action: Short can vanish before TalkBack
        // finishes reading the message aloud.
        duration: SnackbarDuration = SnackbarDuration.Long,
        onAction: (() -> Unit)? = null,
    ) {
        val host = host ?: return
        scope.launch {
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/** A [Messenger] posting to the snackbar host provided by `MainNavigation`. */
@Composable
fun rememberMessenger(): Messenger {
    val scope = rememberCoroutineScope()
    val host = LocalSnackbarHostState.current
    return remember(scope, host) { Messenger(scope, host) }
}
