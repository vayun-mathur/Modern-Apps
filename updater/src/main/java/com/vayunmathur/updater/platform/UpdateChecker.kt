package com.vayunmathur.updater.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.updater.domain.UpdateMetadata
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val TAG = "UpdateChecker"

/** Give up rather than hold a background job open on a captive portal. */
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 15_000

/**
 * Reads the metadata line for this device from the OTA server.
 *
 * MAOS has one channel. `stable` is a constant here rather than a setting because there is
 * nothing to choose between — the publish side still supports channels (`build-maos.sh -c`), so
 * the plumbing exists if a second one is ever wanted, but it is not reachable from the UI.
 */
object UpdateChecker {

    private const val CHANNEL = "stable"

    /** Test seam: replaces opening the HTTPS connection. Null means the real [URL] connection. */
    internal var connectionOverride: ((url: String) -> HttpsURLConnection)? = null

    /** The result of one check. Every failure is a value, not an exception. */
    sealed interface Result {
        /** A build newer than the running one is available. */
        data class Available(val metadata: UpdateMetadata) : Result

        /** The server answered and there is nothing newer. */
        data object UpToDate : Result

        /** Could not reach or could not understand the server. Try again later. */
        data class Failed(val reason: String) : Result
    }

    fun check(): Result {
        val current = SystemBuild.current()
            ?: return Result.Failed("cannot read the running build")
        val device = SystemBuild.device()
        if (device.isEmpty()) return Result.Failed("cannot read the device name")

        val url = "${SystemBuild.otaServer()}/$device-$CHANNEL"
        val body = fetch(url) ?: return Result.Failed("could not reach $url")

        val metadata = UpdateMetadata.parse(body)
            ?: return Result.Failed("server did not return a metadata line")

        return if (com.vayunmathur.updater.domain.UpdateComparison.isNewer(current, metadata)) {
            Result.Available(metadata)
        } else {
            Result.UpToDate
        }
    }

    /**
     * Fetch the metadata body, or null on any failure.
     *
     * HTTPS only, and deliberately not following a redirect to plain HTTP: the signature check
     * on the payload is the real security boundary, but there is no reason to let the metadata
     * be tampered with either. A 404 is normal — it means no release has been published for
     * this device yet.
     */
    private fun fetch(url: String): String? = runCatching {
        val connection = connectionOverride?.invoke(url)
            ?: URL(url).openConnection() as? HttpsURLConnection
            ?: run {
                Log.status(TAG, "refusing a non-HTTPS OTA URL")
                return null
            }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    connection.inputStream.bufferedReader().use { it.readLine() }

                HttpURLConnection.HTTP_NOT_FOUND -> {
                    Log.status(TAG, "no release published for this device yet")
                    null
                }

                else -> {
                    Log.status(TAG, "OTA server returned HTTP $code")
                    null
                }
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.status(TAG, "metadata fetch failed", it) }.getOrNull()
}
