package com.vayunmathur.health.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import com.vayunmathur.library.log.Log

/**
 * Whether Health Connect can be used on this device right now.
 *
 * On Android 14 and later Health Connect is part of the OS, so this is almost always
 * [AVAILABLE]. On Android 13 and below it is a separate APK (`com.google.android.apps.healthdata`)
 * the user must install from the Play Store; when it is missing or outdated
 * [HealthConnectClient.getOrCreate] throws instead of returning a client, which is what crashed
 * the app on launch — before any permission screen — on those releases. Every entry point that
 * touches Health Connect checks this first and shows the install screen instead.
 */
enum class HealthConnectAvailability {
    /** Usable: a client can be created and permissions can be requested. */
    AVAILABLE,

    /** The provider APK is missing: the user must install it. */
    NOT_INSTALLED,

    /** The provider APK is present but too old: the user must update it. */
    UPDATE_REQUIRED,
}

/**
 * Gates Health Connect access on the SDK status and builds the Play Store route for when it is
 * missing.
 *
 * The install URL is the `market://...&url=healthconnect%3A%2F%2Fonboarding` form from the Health
 * Connect onboarding docs: the store opens directly on the Health Connect listing. The `https://`
 * form is the fallback for devices with no store handler.
 */
object HealthConnectSetup {
    private const val TAG = "HealthConnectSetup"

    /** The APK provider's package on Android 13 and below. Also declared in `<queries>`. */
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    /** Play Store listing, opening directly on the Health Connect onboarding flow. */
    const val MARKET_URL =
        "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"

    /** The same listing as a web URL, for devices with no store handler. */
    const val WEB_URL =
        "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"

    /**
     * The current availability. Never throws: an SDK that cannot even report its status is
     * treated as missing, which is the state that used to crash launch.
     */
    // Broad catch is deliberate: this is the availability probe whose contract is to never
    // throw, and the client throws undocumented RuntimeExceptions when the provider is broken.
    @Suppress("TooGenericExceptionCaught")
    fun current(context: Context): HealthConnectAvailability {
        val status = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (e: Exception) {
            Log.status(TAG, "Health Connect status check failed; treating as not installed", e)
            return HealthConnectAvailability.NOT_INSTALLED
        }
        return toAvailability(status)
    }

    /** Maps a [HealthConnectClient.getSdkStatus] result onto [HealthConnectAvailability]. */
    fun toAvailability(sdkStatus: Int): HealthConnectAvailability = when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectAvailability.UPDATE_REQUIRED
        // SDK_UNAVAILABLE covers a missing provider, an unsupported device, and anything new.
        else -> HealthConnectAvailability.NOT_INSTALLED
    }

    /**
     * Opens the Play Store (or a browser fallback) on the provider listing. Returns false when
     * nothing on the device handles either URL, so the caller can say so instead of crashing.
     */
    fun openInstaller(context: Context): Boolean {
        for (url in listOf(MARKET_URL, WEB_URL)) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: ActivityNotFoundException) {
                // No handler for this URL form; try the next one.
            } catch (e: SecurityException) {
                Log.status(TAG, "Not allowed to open the Health Connect installer", e)
                return false
            }
        }
        return false
    }
}
