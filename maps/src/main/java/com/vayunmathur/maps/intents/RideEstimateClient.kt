package com.vayunmathur.maps.intents

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.core.net.toUri
import com.vayunmathur.library.intents.taxi.RideEstimateRequest
import com.vayunmathur.library.intents.taxi.RideEstimateResult

/**
 * Byte-for-byte mirror of the deep-link half of taxi's `RideHandoffContract`.
 *
 * The live fare/ETA estimate is now taxi's `RideEstimateIntent`
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent] (read via [RideEstimateClient]);
 * only the `taxi://book` booking deep link — a separate fire-and-forget hand-off — lives here. As
 * with the P18 family-location and P19 order-lookup channels, the two apps deliberately do NOT
 * share a module: duplicating a scheme, host and a handful of param names keeps maps decoupled from
 * taxi's source while still building the same deep link.
 */
object RideHandoffContract {
    /** Package of the MA taxi app, for the Android 11+ package-visibility / installed check. */
    const val PACKAGE = "com.vayunmathur.taxi"

    const val PARAM_PICKUP_LAT = "pickup_lat"
    const val PARAM_PICKUP_LNG = "pickup_lng"
    const val PARAM_PICKUP_LABEL = "pickup_label"
    const val PARAM_DEST_LAT = "dest_lat"
    const val PARAM_DEST_LNG = "dest_lng"
    const val PARAM_DEST_LABEL = "dest_label"

    const val DEEP_LINK_SCHEME = "taxi"
    const val DEEP_LINK_HOST = "book"

    /** Builds the `taxi://book?…` deep link that opens the taxi app with this trip pre-filled. */
    fun bookingDeepLink(
        pickupLat: Double,
        pickupLng: Double,
        pickupLabel: String?,
        destLat: Double,
        destLng: Double,
        destLabel: String?,
    ): String =
        "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST".toUri().buildUpon().apply {
            appendQueryParameter(PARAM_PICKUP_LAT, pickupLat.toString())
            appendQueryParameter(PARAM_PICKUP_LNG, pickupLng.toString())
            if (!pickupLabel.isNullOrBlank()) appendQueryParameter(PARAM_PICKUP_LABEL, pickupLabel)
            appendQueryParameter(PARAM_DEST_LAT, destLat.toString())
            appendQueryParameter(PARAM_DEST_LNG, destLng.toString())
            if (!destLabel.isNullOrBlank()) appendQueryParameter(PARAM_DEST_LABEL, destLabel)
        }.build().toString()
}

/** Result of a ride estimate: whether a ride is available and, if so, its fare/ETA. */
data class RideEstimate(
    val available: Boolean,
    val fareEstimate: String?,
    val etaMinutes: Int?,
)

/**
 * Reads taxi's exported ride-estimate intent (`com.vayunmathur.taxi.intents.RideEstimateIntent`)
 * for an origin→destination.
 *
 * Absence handling is total: if taxi isn't installed the launch reports a missing package
 * ([MissingAppException]); if the signature permission isn't held it throws [SecurityException]; a
 * timeout or malformed reply throws too. In every one of those cases this returns null and the
 * caller shows the launch-only option (or none) — no crash.
 */
object RideEstimateClient {
    private const val CLASS_NAME = "com.vayunmathur.taxi.intents.RideEstimateIntent"

    /** Whether the MA taxi app is installed (so the option is worth offering at all). */
    fun isInstalled(context: Context): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(RideHandoffContract.PACKAGE, 0)
        }.isSuccess

    suspend fun estimate(
        context: Context,
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double,
        pickupLabel: String? = null,
        destLabel: String? = null,
    ): RideEstimate? {
        return try {
            val result: RideEstimateResult = launchIntent(
                context,
                RideHandoffContract.PACKAGE,
                CLASS_NAME,
                RideEstimateRequest(
                    pickupLat, pickupLng, destLat, destLng,
                    pickupLabel?.ifBlank { null }, destLabel?.ifBlank { null },
                ),
            )
            if (!result.available) {
                RideEstimate(false, null, null)
            } else {
                RideEstimate(
                    true,
                    result.fareEstimate.ifBlank { null },
                    result.etaMinutes.takeIf { it >= 0 },
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Resolves a taxi ride estimate for an origin→destination off the main thread. Emits null while
 * loading and when taxi is absent / the lookup fails; re-resolves whenever the endpoints change.
 */
@Composable
fun rememberRideEstimate(
    context: Context,
    pickupLat: Double,
    pickupLng: Double,
    destLat: Double,
    destLng: Double,
    pickupLabel: String?,
    destLabel: String?,
): State<RideEstimate?> = produceState<RideEstimate?>(
    null, pickupLat, pickupLng, destLat, destLng, pickupLabel, destLabel,
) {
    value = RideEstimateClient.estimate(
        context, pickupLat, pickupLng, destLat, destLng, pickupLabel, destLabel,
    )
}
