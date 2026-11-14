package com.vayunmathur.communicate.data.signal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The device-set disagreements a send can come back with.
 *
 * `409` carries `MismatchedDevices{missingDevices, extraDevices}` — we are missing sessions for some
 * of the recipient's devices and holding sessions for devices that no longer exist. `410` carries
 * `StaleDevices{staleDevices}` — our sessions for those devices are out of date.
 */
data class SignalDeviceMismatch(
    /** Devices needing a fresh session built from a pre-key bundle. */
    val fetch: Set<Int>,
    /** Devices whose session should be archived. */
    val archive: Set<Int>,
) {
    /** Factories and the 409-mismatch parser for [SignalDeviceMismatch]. */
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val HTTP_MISMATCH = 409
        private const val HTTP_STALE = 410

        /** Returns null when the body does not parse or names no devices at all. */
        fun parse(status: Int, body: String): SignalDeviceMismatch? {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (_: Exception) {
                return null
            }
            val result = when (status) {
                HTTP_MISMATCH -> SignalDeviceMismatch(
                    fetch = root.ints("missingDevices"),
                    archive = root.ints("extraDevices"),
                )
                // Stale sessions are archived to keep the old chain readable, then rebuilt from fresh
                // pre-keys. Archiving alone would leave the device unsendable until the server came
                // back with a 409 naming it.
                HTTP_STALE -> root.ints("staleDevices").let { stale ->
                    SignalDeviceMismatch(fetch = stale, archive = stale)
                }
                else -> return null
            }
            return if (result.fetch.isEmpty() && result.archive.isEmpty()) null else result
        }

        private fun kotlinx.serialization.json.JsonObject.ints(key: String): Set<Int> = try {
            this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.int }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
