package com.vayunmathur.taxi.intents

import android.content.Context
import com.vayunmathur.library.intents.maps.DirectionsRequest
import com.vayunmathur.library.intents.maps.DirectionsResult

/**
 * Wire contract for maps' `DirectionsIntent`
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent].
 *
 * As with the other cross-app channels, the two apps deliberately do NOT share a module: the
 * contract is a package name, an activity class name and a signature permission, so duplicating
 * the three constants here keeps taxi decoupled from maps' source while still addressing the
 * same exported activity. The shared request/response DTOs live in `:library`
 * (`com.vayunmathur.library.intents.maps`).
 */
object DirectionsProtocol {
    /** Package of the MA maps app, for the Android 11+ package-visibility / installed check. */
    const val PACKAGE = "com.vayunmathur.maps"

    /** The exported NoDisplay activity that answers a [DirectionsRequest]. */
    const val CLASS_NAME = "com.vayunmathur.maps.intents.DirectionsIntent"

    /** Signature-level permission maps guards the activity with (both MA apps share the key). */
    const val PERMISSION = "com.vayunmathur.maps.permissions.ACCESS_MAPS"

    /** Driving mode — the only mode taxi asks for. Matched case-insensitively by the host. */
    const val MODE_DRIVE = "drive"
}

/**
 * Asks the maps app to plan a route between two points via [DirectionsProtocol].
 *
 * Absence handling is total: if maps isn't installed the launch reports a missing package
 * ([MissingAppException]); if the signature permission isn't held it throws [SecurityException];
 * a timeout or malformed reply throws too. In every one of those cases this returns null and the
 * caller simply draws no route — no crash.
 */
object DirectionsClient {
    suspend fun directions(
        context: Context,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        mode: String = DirectionsProtocol.MODE_DRIVE,
    ): DirectionsResult? =
        try {
            launchIntent<DirectionsRequest, DirectionsResult>(
                context,
                DirectionsProtocol.PACKAGE,
                DirectionsProtocol.CLASS_NAME,
                DirectionsRequest(fromLat, fromLng, toLat, toLng, mode),
            )
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
}
