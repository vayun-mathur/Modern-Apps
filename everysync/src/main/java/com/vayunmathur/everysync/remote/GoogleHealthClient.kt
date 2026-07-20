package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import com.vayunmathur.library.network.NetworkClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google Health API v4 (`health.googleapis.com`), replacing the wound-down Google
 * Fitness REST API. Pulls per-day rollups (`dataPoints:dailyRollUp`) for steps,
 * weight, and heart rate and maps them to [RemoteMeasurement]s for Health
 * Connect. The civil day start makes the clientRecordId idempotent across syncs.
 */
class GoogleHealthClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun headers() = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Content-Type" to "application/json",
    )

    suspend fun getMeasurements(sinceMillis: Long): List<RemoteMeasurement> =
        SPECS.flatMap { dailyRollUp(it, sinceMillis) }

    private suspend fun dailyRollUp(spec: Spec, sinceMillis: Long): List<RemoteMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val sinceDate = if (sinceMillis > 0) {
            Instant.ofEpochMilli(sinceMillis).atZone(zone).toLocalDate()
        } else {
            today.minusDays(DEFAULT_LOOKBACK_DAYS)
        }
        // Clamp to the data type's server-side maximum civil-day range.
        val earliest = today.minusDays(spec.maxRangeDays.toLong())
        val start = if (sinceDate.isBefore(earliest)) earliest else sinceDate
        val end = today.plusDays(1) // exclusive end, so today is included

        val body = """
            {
              "range": {
                "start": {"date": ${dateJson(start)}},
                "end": {"date": ${dateJson(end)}}
              },
              "windowSizeDays": 1
            }
        """.trimIndent()

        val out = mutableListOf<RemoteMeasurement>()
        try {
            val resp = NetworkClient.performRequest(
                "https://health.googleapis.com/v4/users/me/dataTypes/${spec.dataType}/dataPoints:dailyRollUp",
                "POST", headers(), body,
            )
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: return out
            (root["rollupDataPoints"] as? JsonArray)?.forEach { el ->
                val rollup = el.jsonObject
                val dayMillis = civilStartMillis(rollup, zone) ?: return@forEach
                val value = spec.extract(rollup) ?: return@forEach
                out += RemoteMeasurement(
                    clientRecordId = "googlehealth:${spec.type.name}:$dayMillis",
                    type = spec.type,
                    value = value,
                    timeMillis = dayMillis,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "dailyRollUp(${spec.dataType}) failed", e)
        }
        return out
    }

    private fun dateJson(d: LocalDate): String =
        """{"year": ${d.year}, "month": ${d.monthValue}, "day": ${d.dayOfMonth}}"""

    /** Convert a rollup point's `civilStartTime.date` to epoch millis at local midnight. */
    private fun civilStartMillis(rollup: JsonObject, zone: ZoneId): Long? {
        val date = rollup["civilStartTime"]?.jsonObject?.get("date")?.jsonObject ?: return null
        val year = date["year"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val month = date["month"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val day = date["day"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        return LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private data class Spec(
        /** Data type path segment, kebab-case per the v4 API. */
        val dataType: String,
        val type: MeasurementType,
        /** Server-side max civil-day range for `dailyRollUp` on this type. */
        val maxRangeDays: Int,
        /** Pull the value out of a rollup data point, applying unit conversion. */
        val extract: (JsonObject) -> Double?,
    )

    companion object {
        private const val TAG = "GoogleHealthClient"
        private const val DEFAULT_LOOKBACK_DAYS = 30L

        private val SPECS = listOf(
            Spec("steps", MeasurementType.STEPS, maxRangeDays = 90) {
                it.rollupValue("steps", "countSum")
            },
            // weightGramsAvg is in grams; Health Connect wants kilograms.
            Spec("weight", MeasurementType.WEIGHT, maxRangeDays = 90) {
                it.rollupValue("weight", "weightGramsAvg")?.div(1000.0)
            },
            Spec("heart-rate", MeasurementType.HEART_RATE, maxRangeDays = 14) {
                it.rollupValue("heartRate", "beatsPerMinuteAvg")
            },
        )

        /** Read a numeric field nested under a rollup value object (int64 arrives as a string). */
        private fun JsonObject.rollupValue(field: String, key: String): Double? =
            this[field]?.jsonObject?.get(key)?.jsonPrimitive?.content?.toDoubleOrNull()
    }
}
