package com.vayunmathur.networklocation.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

private const val UPDATE_INTERVAL_MS = 1_000L

fun hasFineLocation(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Fixes from one system provider, starting with its last known fix. Emits nothing if the
 * provider does not exist on this device or location permission is missing.
 */
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context, provider: String): Flow<Location> = callbackFlow {
    val lm = context.getSystemService(LocationManager::class.java)
    if (lm == null || !lm.hasProvider(provider) || !hasFineLocation(context)) {
        awaitClose { }
        return@callbackFlow
    }
    lm.getLastKnownLocation(provider)?.let { trySend(it) }
    val listener = LocationListener { trySend(it) }
    lm.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
    awaitClose { lm.removeUpdates(listener) }
}

/**
 * Reverse-geocode through the system [Geocoder], i.e. through this app's own
 * `GeocodeService` exactly as any other app would reach it. Calling the native reader directly
 * would open a second copy of the multi-gigabyte database in this process.
 */
suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): Result<Address?> {
    val geocoder = Geocoder(context, Locale.getDefault())
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    cont.resume(Result.success(addresses.firstOrNull()))
                }

                override fun onError(errorMessage: String?) {
                    cont.resume(Result.failure(IllegalStateException(errorMessage ?: "geocoder error")))
                }
            })
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }
        }
    }
}
