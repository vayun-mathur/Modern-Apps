package com.vayunmathur.findfamily.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest

/** Mutable chain state for [rememberLocationSharingAction]; held across the sequential requests. */
private class LocationSharingChain {
    var pending: (() -> Unit)? = null
    var step: Int = 0
    var advance: (() -> Unit)? = null

    fun deny(onDenied: () -> Unit) {
        pending = null
        step = 0
        onDenied()
    }

    fun finish() {
        val action = pending
        pending = null
        step = 0
        action?.invoke()
    }
}

/**
 * Contextual location-sharing permission, in the pattern used elsewhere in
 * the suite (e.g. photos' media-management action): wrap the action that
 * enables sharing — if foreground → background → Bluetooth-scan are already
 * granted the action runs immediately, otherwise they are requested in that
 * order and the held action runs once on grant. Still denied drops the
 * action untouched and reports through [onDenied].
 *
 * Background location is only requested after foreground is granted (the
 * system ignores it otherwise), which is why the chain is sequential rather
 * than one batch.
 */
@Composable
fun rememberLocationSharingAction(
    onDenied: () -> Unit = {},
): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val requirements = remember { findFamilyLocationSharingRequirements() }
    val chain = remember { LocationSharingChain() }

    fun granted(index: Int): Boolean = requirements[index].isGranted(context)

    // Unrolled, not a loop, so the three launchers keep a stable hook order.
    val runtime = requirements.map {
        it as com.vayunmathur.library.ui.PermissionRequirement.Runtime
    }
    val launch0 = rememberMultiplePermissionRequest(runtime[0].permissions) { allGranted ->
        if (allGranted) chain.advance?.invoke() else chain.deny(onDenied)
    }
    val launch1 = rememberMultiplePermissionRequest(runtime[1].permissions) { allGranted ->
        if (allGranted) chain.advance?.invoke() else chain.deny(onDenied)
    }
    val launch2 = rememberMultiplePermissionRequest(runtime[2].permissions) { allGranted ->
        if (allGranted) chain.advance?.invoke() else chain.deny(onDenied)
    }
    val launchers = listOf(launch0, launch1, launch2)

    chain.advance = {
        chain.step += 1
        when {
            chain.step >= requirements.size -> chain.finish()
            granted(chain.step) -> chain.advance?.invoke()
            else -> launchers[chain.step]()
        }
    }

    return remember {
        { action: () -> Unit ->
            chain.pending = action
            chain.step = 0
            if (granted(0)) {
                chain.advance?.invoke()
            } else {
                launchers[0]()
            }
        }
    }
}
