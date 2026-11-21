package com.vayunmathur.findfamily.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest

/**
 * Contextual notifications, in the pattern used elsewhere in the suite:
 * wrap the first alert-relevant action (setting a no-show watch, saving a
 * place whose arrivals are announced) — if POST_NOTIFICATIONS is already
 * granted (or not runtime-grantable pre-Tiramisu, where the requirement is
 * an empty batch) the action runs immediately, otherwise it is requested
 * and the held action runs once on grant. Still denied drops the action
 * untouched and reports through [onDenied], so an alert is never silently
 * saved in a state where it can never be announced.
 */
@Composable
fun rememberNotificationPermissionAction(
    onDenied: () -> Unit = {},
): (action: () -> Unit) -> Unit {
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val request = rememberMultiplePermissionRequest(
        findFamilyNotificationRequirement().permissions,
    ) { allGranted ->
        val action = pending.value
        pending.value = null
        if (allGranted) action?.invoke() else onDenied()
    }
    return remember {
        { action: () -> Unit ->
            pending.value = action
            request()
        }
    }
}
