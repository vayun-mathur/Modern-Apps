package com.vayunmathur.library.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/**
 * One blocking requirement of the consolidated first-run gate.
 *
 * Each requirement knows how to check itself synchronously via [isGranted], so the
 * gate ([AppPermissionsGate]) can decide between the permission screen and the app's
 * content without per-app state. Requirements that only exist on newer releases
 * report granted on older ones (the [SpecialAccess] convention), so callers list
 * everything unconditionally.
 *
 * Ordering is significant: the gate requests and displays in list order, which is
 * what gives multi-step flows (e.g. findfamily foreground → background → bluetooth)
 * their sequence for free.
 */
sealed interface PermissionRequirement {

    /** Synchronous check; true means this requirement is satisfied. */
    fun isGranted(context: Context): Boolean

    /** Ordinary Android runtime permissions, requested as one batch. */
    data class Runtime(val permissions: Array<String>) : PermissionRequirement {
        override fun isGranted(context: Context): Boolean = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Runtime) return false
            return permissions.contentEquals(other.permissions)
        }

        override fun hashCode(): Int = permissions.contentHashCode()
    }

    /** Exact alarms behind the Settings page (pre-Tiramisu revocable). */
    data object ExactAlarms : PermissionRequirement {
        override fun isGranted(context: Context): Boolean = SpecialAccess.hasExactAlarms(context)
    }

    /** "All files access" for browsing outside the media collections. */
    data object AllFiles : PermissionRequirement {
        override fun isGranted(context: Context): Boolean = SpecialAccess.hasAllFilesAccess()
    }

    /** Notification-listener access for reading other apps' notifications. */
    data object NotificationListener : PermissionRequirement {
        override fun isGranted(context: Context): Boolean =
            SpecialAccess.hasNotificationListener(context)
    }

    /**
     * VPN consent, via `VpnService.prepare`: a null prepare intent means granted.
     *
     * The check calls `prepare()` (side-effect free); the row tap launches the
     * returned intent through [SpecialAccess.requestVpnConsent].
     */
    data object Vpn : PermissionRequirement {
        override fun isGranted(context: Context): Boolean = SpecialAccess.hasVpnConsent(context)
    }

    /** Full-screen-intent permission for alarm-style notifications (API 34+). */
    data object FullScreenIntent : PermissionRequirement {
        override fun isGranted(context: Context): Boolean =
            SpecialAccess.hasFullScreenIntent(context)
    }

    /** Media-management access for editing/deleting shared media. */
    data object ManageMedia : PermissionRequirement {
        override fun isGranted(context: Context): Boolean =
            SpecialAccess.hasManageMedia(context)
    }

    /**
     * Health Connect permissions (their own contract, not runtime permissions).
     *
     * Granted-state is async (`getGrantedPermissions` is a suspend call), so the
     * synchronous check reports the last probed value: the gate probes on launch
     * and on every resume and caches it. Until the first probe completes the gate
     * holds on a loading state rather than flashing the wall.
     */
    data class HealthConnect(val permissions: Set<String>) : PermissionRequirement {
        override fun isGranted(context: Context): Boolean =
            HealthConnectGrantCache.granted?.containsAll(permissions) == true
    }

    /** Factories for the common permission requirements. */
    companion object {
        /**
         * Runtime POST_NOTIFICATIONS, or nothing on releases where it is not
         * runtime-grantable (pre-Tiramisu): an empty array reports granted, so
         * callers gate on the returned requirement unconditionally.
         */
        fun notifications(): Runtime = Runtime(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        )
    }
}

/** Last probed Health Connect grant set, shared by the gate's async probe. */
internal object HealthConnectGrantCache {
    @Volatile
    var granted: Set<String>? = null
}

/** What the consolidated gate shows and requires. */
data class AppPermissionsSpec(
    val title: String,
    val requirements: List<PermissionRequirement>,
    val subtitle: String? = null,
    val icon: (@Composable () -> Unit)? = null,
)
