package com.vayunmathur.setupwizard.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.vayunmathur.library.log.Log
import java.lang.reflect.InvocationTargetException
import java.util.Locale

private const val TAG = "SystemSetup"

/**
 * Every framework call the wizard makes that the public SDK does not expose.
 *
 * A setup wizard is almost entirely made of these: setting the system locale, reading the
 * bootloader lock state, disabling the status bar for the duration, setting the clock. All of
 * them are `@SystemApi` or `@hide`, so they are absent from the SDK this module compiles
 * against and have to be reached reflectively. The permissions are still enforced on the
 * framework side - the reflection buys visibility, not privilege - so none of this works
 * unless the APK is platform-signed and whitelisted in `privapp-permissions-modern-apps.xml`.
 *
 * Nothing here throws. A missing method means an OS that does not have the feature, and the
 * only screen that could report the failure is the one that has not been drawn yet: this runs
 * before the device is provisioned, so an uncaught exception is not a crash the user can back
 * out of, it is a device that never finishes first boot. Every call degrades to the reading
 * that keeps the flow moving instead.
 */
class SystemSetup(context: Context) {

    private val appContext = context.applicationContext

    // ---- identity -------------------------------------------------------------------------

    /**
     * The device owner, as opposed to a secondary user setting up their own profile. Decides
     * which steps run at all - see [SetupFlow].
     *
     * Defaults to true when unreadable, because the owner flow is the superset: a secondary
     * user would see a few steps that do nothing for them, where the reverse would silently
     * skip Wi-Fi and the clock on a first boot.
     */
    val isPrimaryUser: Boolean by lazy {
        val users = appContext.getSystemService(UserManager::class.java)
        hidden("UserManager.isSystemUser", true) {
            UserManager::class.java.getMethod("isSystemUser").invoke(users) as Boolean
        }
    }

    /**
     * Whether this is a debuggable OS build, which unlocks the [DebugFlags] overrides and
     * leaves the "disable OEM unlocking" box unticked by default.
     *
     * `Build.isDebuggable()` reads `ro.debuggable`; `Build.TYPE` is the public shadow of the
     * same thing and is the fallback.
     */
    val isOsDebuggable: Boolean by lazy {
        hidden("Build.isDebuggable", Build.TYPE == "userdebug" || Build.TYPE == "eng") {
            Build::class.java.getMethod("isDebuggable").invoke(null) as Boolean
        }
    }

    // ---- status bar -----------------------------------------------------------------------

    /**
     * Locks down the status bar for the duration of setup: no notifications, no shade, no home
     * and no recents, so the wizard cannot be escaped before the device is provisioned.
     */
    fun setStatusBarHiddenForSetup(hide: Boolean) {
        val statusBar = appContext.getSystemService(StatusBarManager::class.java) ?: return
        hidden("StatusBarManager.setDisabledForSetup", Unit) {
            StatusBarManager::class.java
                .getMethod("setDisabledForSetup", Boolean::class.javaPrimitiveType)
                .invoke(statusBar, hide)
            Unit
        }
    }

    // ---- locale ---------------------------------------------------------------------------

    /** The system locale, re-read from the global configuration rather than cached. */
    fun currentLocale(): Locale =
        Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()

    /**
     * The locales the framework itself ships resources for, which is the same set the system
     * language picker offers, sorted by how they read in their own language.
     *
     * Built from the public `AssetManager.getLocales()` rather than the internal
     * `LocalePicker.getAllAssetLocales`, which is what Settings builds its list from. Entries
     * with no display name of their own (the empty root locale, pseudo-locales) are dropped -
     * they would render as a blank row.
     */
    fun availableLocales(): List<Locale> = Resources.getSystem().assets.locales
        .asSequence()
        .filter { it.isNotBlank() }
        .map { Locale.forLanguageTag(it.replace('_', '-')) }
        .filter { it.language.isNotEmpty() && it.getDisplayName(it).isNotBlank() }
        .distinctBy { it.toLanguageTag() }
        .sortedBy { it.getDisplayName(it).lowercase(it) }
        .toList()

    /**
     * Changes the system locale for every user and persists it.
     *
     * There is no public equivalent: `LocaleManager.setSystemLocales` is itself `@SystemApi`,
     * and the per-app locale API deliberately cannot reach the system one.
     */
    fun setSystemLocale(locale: Locale) {
        hidden("LocalePicker.updateLocale", Unit) {
            Class.forName("com.android.internal.app.LocalePicker")
                .getMethod("updateLocale", Locale::class.java)
                .invoke(null, locale)
            Unit
        }
    }

    /** The locale the inserted SIM asks for, or null when there is no SIM or it names none. */
    fun simLocale(): Locale? {
        val telephony = appContext.getSystemService(TelephonyManager::class.java) ?: return null
        return hidden("TelephonyManager.getSimLocale", null) {
            TelephonyManager::class.java.getMethod("getSimLocale").invoke(telephony) as Locale?
        }
    }

    // ---- bootloader -----------------------------------------------------------------------

    /**
     * Whether the bootloader is unlocked, i.e. the device will boot an image someone else
     * signed. Drives the warning on the welcome step and the whole bootloader step.
     *
     * Defaults to false when unreadable so the warning is not shown on a device that may well
     * be locked; the OEM-unlock step is advisory, and claiming a locked device is unlocked
     * would be its own kind of wrong.
     */
    fun isDeviceOemUnlocked(): Boolean {
        if (isOsDebuggable) DebugFlags.getBool(appContext, "isDeviceOemUnlocked_override")
            ?.let { return it }
        val oemLock = oemLockManager() ?: return false
        return hidden("OemLockManager.isDeviceOemUnlocked", false) {
            oemLock.javaClass.getMethod("isDeviceOemUnlocked").invoke(oemLock) as Boolean
        }
    }

    /**
     * Whether the user has allowed the bootloader to be unlocked in future - the
     * "OEM unlocking" developer-options toggle, not the current lock state.
     */
    fun isOemUnlockAllowedByUser(): Boolean {
        if (isOsDebuggable) DebugFlags.getBool(appContext, "isOemUnlockAllowedByUser_override")
            ?.let { return it }
        val oemLock = oemLockManager() ?: return false
        return hidden("OemLockManager.isOemUnlockAllowedByUser", false) {
            oemLock.javaClass.getMethod("isOemUnlockAllowedByUser").invoke(oemLock) as Boolean
        }
    }

    /**
     * Turns the "OEM unlocking" toggle off, which is the one thing the final step offers to do.
     *
     * A carrier or policy can forbid unlocking outright, in which case the framework rejects
     * the write with a SecurityException even though the toggle is already effectively off.
     * That is not an error worth surfacing - the user asked for unlocking to be off and it is.
     * See https://discuss.grapheneos.org/d/17678/15.
     */
    fun setOemUnlockAllowedByUser(allowed: Boolean) {
        val oemLock = oemLockManager() ?: return
        try {
            oemLock.javaClass
                .getMethod("setOemUnlockAllowedByUser", Boolean::class.javaPrimitiveType)
                .invoke(oemLock, allowed)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            if (cause is SecurityException && !isOemUnlockAllowed(oemLock)) {
                Log.debug(TAG, "setOemUnlockAllowedByUser($allowed): unlocking is not allowed", cause)
            } else {
                Log.error(TAG, "setOemUnlockAllowedByUser($allowed) failed", cause ?: e)
            }
        } catch (e: ReflectiveOperationException) {
            Log.status(TAG, "OemLockManager.setOemUnlockAllowedByUser unavailable on this build", e)
        }
    }

    private fun isOemUnlockAllowed(oemLock: Any): Boolean =
        hidden("OemLockManager.isOemUnlockAllowed", false) {
            oemLock.javaClass.getMethod("isOemUnlockAllowed").invoke(oemLock) as Boolean
        }

    /** `android.service.oemlock.OemLockManager` is absent entirely on devices with no OEM lock. */
    private fun oemLockManager(): Any? = appContext.getSystemService("oem_lock")

    fun rebootToBootloader() {
        appContext.getSystemService(PowerManager::class.java)?.reboot("bootloader")
    }

    // ---- location -------------------------------------------------------------------------

    fun isLocationEnabled(): Boolean =
        appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    fun setLocationEnabled(enabled: Boolean) {
        val locations = appContext.getSystemService(LocationManager::class.java) ?: return
        hidden("LocationManager.setLocationEnabledForUser", Unit) {
            LocationManager::class.java.getMethod(
                "setLocationEnabledForUser",
                Boolean::class.javaPrimitiveType,
                android.os.UserHandle::class.java,
            ).invoke(locations, enabled, Process.myUserHandle())
            Unit
        }
    }

    /** The getter is public (deprecated for third-party apps); only the setter is hidden. */
    @Suppress("DEPRECATION")
    fun isWifiScanningAlwaysAvailable(): Boolean =
        appContext.getSystemService(WifiManager::class.java)?.isScanAlwaysAvailable == true

    fun setWifiScanningAlwaysAvailable(enabled: Boolean) {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
        hidden("WifiManager.setScanAlwaysAvailable", Unit) {
            WifiManager::class.java
                .getMethod("setScanAlwaysAvailable", Boolean::class.javaPrimitiveType)
                .invoke(wifi, enabled)
            Unit
        }
    }

    // ---- lock screen ----------------------------------------------------------------------

    fun isDeviceSecure(): Boolean =
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    // ---- clock ----------------------------------------------------------------------------

    /**
     * Turn on network time and time zone.
     *
     * Setup no longer has a date-and-time step. Asking someone to set a clock by hand is work
     * the network does better and immediately, and the manual values were being overwritten by
     * the first NITZ/NTP sync anyway. Both flags are written by the owner only: they are
     * [Settings.Global], so they belong to the device rather than to a profile.
     *
     * Best effort. WRITE_SETTINGS covers these, but a build that has not granted it should lose
     * the automatic clock, not the whole of setup.
     */
    fun enableAutomaticTime() {
        if (!isPrimaryUser) return
        runCatching {
            Settings.Global.putInt(appContext.contentResolver, Settings.Global.AUTO_TIME, 1)
            Settings.Global.putInt(appContext.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1)
        }.onFailure { Log.status(TAG, "could not enable automatic time", it) }
    }

    // ---- displays -------------------------------------------------------------------------

    /**
     * Default a cast display to mirroring rather than extending.
     *
     * `enable_display_content_mode_management` moved the platform default for a connected
     * display to extended, so casting puts an empty desktop on the TV until someone finds the
     * switch buried on that display's page under Connected Displays. Mirroring is what casting
     * is taken to mean; the switch still overrides this.
     *
     * Written only when the setting is absent, never when it is present and zero:
     * `finishSetup` runs again on every launch after setup, and an unconditional write would
     * undo a deliberate choice of desktop mode each time. [Settings.Secure], so each user seeds
     * their own.
     */
    fun seedDisplayMirroring() {
        runCatching {
            val resolver = appContext.contentResolver
            if (Settings.Secure.getString(resolver, SETTING_MIRROR_BUILT_IN_DISPLAY) != null) {
                return@runCatching
            }
            Settings.Secure.putInt(resolver, SETTING_MIRROR_BUILT_IN_DISPLAY, 1)
        }.onFailure { Log.status(TAG, "could not seed display mirroring", it) }
    }

    // ---- emergency dialer -----------------------------------------------------------------

    /**
     * The dialer the welcome step's secondary button opens. Reachable before provisioning,
     * which the ordinary dial intent is not.
     */
    fun emergencyDialerIntent(): Intent {
        val telecom = appContext.getSystemService(TelecomManager::class.java)
        val fallback = Intent(ACTION_EMERGENCY_DIAL)
        if (telecom == null) return fallback
        return hidden("TelecomManager.createLaunchEmergencyDialerIntent", fallback) {
            TelecomManager::class.java
                .getMethod("createLaunchEmergencyDialerIntent", String::class.java)
                .invoke(telecom, null) as Intent? ?: fallback
        }
    }

    // ---- provisioning ---------------------------------------------------------------------

    /**
     * True once this user has been through the wizard. The welcome step checks it and bails
     * straight to completion, so re-resolving HOME after setup cannot drop the user back into
     * first-run.
     */
    fun isUserSetupComplete(): Boolean =
        Settings.Secure.getInt(appContext.contentResolver, SETTING_USER_SETUP_COMPLETE, 0) == 1

    /**
     * The write that ends setup. `DEVICE_PROVISIONED` is global and belongs to the device, so
     * only the owner sets it; `user_setup_complete` is per user and every user sets their own.
     *
     * Best effort like the clock and display seeds above: both writes need a privapp
     * whitelist grant, and a build that has not granted it should lose the provisioning
     * flags, not abort setup after the user already pressed finish - the exception would
     * skip task removal, `finish()`, the status-bar restore and `disableSelf()`.
     */
    fun markSetupComplete(activity: Activity) {
        if (isPrimaryUser) {
            runCatching {
                Settings.Global.putInt(
                    activity.contentResolver,
                    Settings.Global.DEVICE_PROVISIONED,
                    1,
                )
            }.onFailure { Log.status(TAG, "could not mark device provisioned", it) }
        }
        runCatching {
            Settings.Secure.putInt(activity.contentResolver, SETTING_USER_SETUP_COMPLETE, 1)
        }.onFailure { Log.status(TAG, "could not mark user setup complete", it) }
    }

    /**
     * Takes the wizard out of the recents list as well as off the screen, so the first thing
     * the user does after setup is not swipe back into it.
     */
    fun finishAllTasks(activity: Activity) {
        activity.getSystemService(ActivityManager::class.java)?.appTasks?.forEach {
            it.finishAndRemoveTask()
        }
    }

    /**
     * Disables the wizard's own package once setup is done. It is a HOME activity at priority
     * 999; left enabled it would keep winning HOME resolution against the launcher.
     */
    fun disableSelf() {
        appContext.packageManager.setApplicationEnabledSetting(
            appContext.packageName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private companion object {
        const val ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL"

        /** `Settings.Secure.USER_SETUP_COMPLETE` is itself `@hide`. */
        const val SETTING_USER_SETUP_COMPLETE = "user_setup_complete"

        /** `Settings.Secure.MIRROR_BUILT_IN_DISPLAY` is itself `@hide`. */
        const val SETTING_MIRROR_BUILT_IN_DISPLAY = "mirror_built_in_display"
    }
}

/**
 * Runs a reflective framework call, returning [fallback] if the method is not on this build or
 * threw. See the note on [SystemSetup] for why nothing here is allowed to propagate.
 */
private inline fun <T> hidden(what: String, fallback: T, block: () -> T): T = try {
    block()
} catch (e: ReflectiveOperationException) {
    Log.status(TAG, "$what unavailable or failed on this build", e)
    fallback
}
