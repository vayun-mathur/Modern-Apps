package com.vayunmathur.library.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The permissions that cannot be requested with an `ActivityResultContract`
 * and have to be granted in system settings instead.
 *
 * These are why [PermissionWall] takes an `onRequest` lambda rather than a
 * permission array: there is nothing to request. All any app can do is send
 * the user to the right settings page, and each app was hand-rolling that
 * intent - which is easy to get wrong, since several of these need the package
 * URI and some only exist on newer releases.
 *
 * Each returns false when the platform is too old for that page to exist, so
 * callers can skip the wall entirely rather than launching an intent that
 * resolves to nothing.
 */
object SpecialAccess {

    private fun Context.launch(intent: Intent) =
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun packageUri(context: Context) =
        Uri.fromParts("package", context.packageName, null)

    /** "All files access" - needed to browse storage outside the media collections. */
    fun hasAllFilesAccess(): Boolean = android.os.Environment.isExternalStorageManager()

    fun requestAllFilesAccess(context: Context) {
        context.launch(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                packageUri(context),
            )
        )
    }

    /**
     * Exact alarms, required for anything that must fire at a precise time.
     *
     * On API 33+ the `USE_EXACT_ALARM` permission is install-granted and
     * non-revocable, but a denial can still happen (e.g. Play policy rejects the
     * declaration on update, or the grant is otherwise missing), so the
     * `AlarmManager.canScheduleExactAlarms()` state is always checked and a
     * denial routes through the `AppPermissionsGate` exact-alarms prompt instead
     * of silently degrading to inexact alarms.
     */
    fun hasExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        return manager?.canScheduleExactAlarms() ?: false
    }

    fun requestExactAlarms(context: Context) {
        context.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
    }

    /** Notification listener access, for reading other apps' notifications. */
    fun hasNotificationListener(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    fun requestNotificationListener(context: Context) =
        context.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    /** The always-on VPN page; there is no way to query consent up front. */
    fun openVpnSettings(context: Context) =
        context.launch(Intent(Settings.ACTION_VPN_SETTINGS))

    /**
     * VPN consent via `VpnService.prepare`: null means already granted, otherwise
     * the returned intent is what the user must approve.
     *
     * The check itself is side-effect free; [requestVpnConsent] launches the
     * approval when there is one. Apps using the legacy settings page keep
     * [openVpnSettings].
     */
    fun hasVpnConsent(context: Context): Boolean =
        runCatching {
            android.net.VpnService.prepare(context) == null
        }.getOrDefault(false)

    fun requestVpnConsent(context: Context) {
        val approval = runCatching { android.net.VpnService.prepare(context) }.getOrNull()
        if (approval != null) {
            context.launch(approval)
        } else {
            openVpnSettings(context)
        }
    }

    /** Media-management access for editing or deleting shared media. */
    fun hasManageMedia(context: Context): Boolean =
        android.provider.MediaStore.canManageMedia(context)

    fun requestManageMedia(context: Context) {
        context.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA))
    }

    /** Full-screen intent permission, for alarm-style full screen notifications. */
    fun hasFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        return manager?.canUseFullScreenIntent() ?: false
    }

    fun requestFullScreenIntent(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.launch(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
            )
        } else {
            openAppSettings(context)
        }
    }
}
