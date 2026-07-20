package com.vayunmathur.everysync.remote

import android.net.Uri
import android.util.Log
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import com.vayunmathur.everysync.model.RemoteSleepStage
import com.vayunmathur.library.network.NetworkClient
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google Health API v4 (`health.googleapis.com`), replacing the wound-down Google
 * Fitness REST API. Pulls every metric Health Connect can store and maps them to
 * [RemoteMeasurement]s.
 *
 * Fetch strategy per type:
 * - [Mode.ROLLUP] — `dataPoints:dailyRollUp`, one aggregated value per civil day
 *   (steps, distance, floors, calories, heart rate).
 * - [Mode.LIST_SAMPLE] — `dataPoints` list of instantaneous samples at their real
 *   timestamps (weight, height, body fat, glucose, temperature, VO2 max, HRV).
 * - [Mode.LIST_DAILY] — `dataPoints` list of per-day summaries (resting HR,
 *   oxygen saturation, respiratory rate).
 * - [Mode.LIST_SESSION] — `dataPoints` list of sessions with a scalar value
 *   (hydration).
 * - Sleep is fetched separately with its stage segments.
 *
 * The point/day/session start makes the clientRecordId idempotent across syncs.
 */
class GoogleHealthClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun headers() = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Content-Type" to "application/json",
    )

    suspend fun getMeasurements(sinceMillis: Long): List<RemoteMeasurement> {
        val out = mutableListOf<RemoteMeasurement>()
        for (spec in SPECS) {
            out += if (spec.mode == Mode.ROLLUP) dailyRollUp(spec, sinceMillis) else listDataPoints(spec, sinceMillis)
        }
        out += fetchSleep(sinceMillis)
        return out
    }

    // --- dailyRollUp (steps, distance, floors, calories, heart rate) ---

    private suspend fun dailyRollUp(spec: Spec, sinceMillis: Long): List<RemoteMeasurement> {
        val zone = ZoneId.systemDefault()
        val (start, end) = window(spec.maxRangeDays, sinceMillis, zone)
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
                val dayMillis = dateMillis(rollup.nestedObject("civilStartTime", "date"), zone) ?: return@forEach
                val value = rollup.nestedDouble(spec.jsonField, *spec.valuePath)?.times(spec.scale) ?: return@forEach
                out += RemoteMeasurement(
                    clientRecordId = recordId(spec.type, dayMillis),
                    type = spec.type,
                    value = value,
                    startMillis = dayMillis,
                    endMillis = dayMillis + DAY_MILLIS,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "dailyRollUp(${spec.dataType}) failed", e)
        }
        return out
    }

    // --- dataPoints list (samples, daily summaries, hydration sessions) ---

    private suspend fun listDataPoints(spec: Spec, sinceMillis: Long): List<RemoteMeasurement> {
        val zone = ZoneId.systemDefault()
        val (start, end) = window(spec.maxRangeDays, sinceMillis, zone)
        val filter = when (spec.mode) {
            Mode.LIST_DAILY ->
                "${spec.filterField}.date >= \"$start\" AND ${spec.filterField}.date < \"$end\""
            Mode.LIST_SESSION ->
                "${spec.filterField}.interval.civil_start_time >= \"$start\" AND " +
                    "${spec.filterField}.interval.civil_start_time < \"$end\""
            else ->
                "${spec.filterField}.sample_time.physical_time >= \"${iso(start, zone)}\" AND " +
                    "${spec.filterField}.sample_time.physical_time < \"${iso(end, zone)}\""
        }

        val out = mutableListOf<RemoteMeasurement>()
        try {
            forEachDataPoint(spec.dataType, filter) { dp ->
                val (startMs, endMs) = when (spec.mode) {
                    Mode.LIST_DAILY -> dateMillis(dp.nestedObject(spec.jsonField, "date"), zone)?.let { it to it }
                    Mode.LIST_SESSION -> {
                        val interval = dp.nestedObject(spec.jsonField, "interval")
                        val s = sampleMillis(interval?.get("startTime"))
                        s?.let { it to (sampleMillis(interval?.get("endTime")) ?: it) }
                    }
                    else -> sampleMillis(dp.nestedObject(spec.jsonField, "sampleTime")?.get("physicalTime"))?.let { it to it }
                } ?: return@forEachDataPoint
                val value = dp.nestedDouble(spec.jsonField, *spec.valuePath)?.times(spec.scale) ?: return@forEachDataPoint
                out += RemoteMeasurement(
                    clientRecordId = recordId(spec.type, startMs),
                    type = spec.type,
                    value = value,
                    startMillis = startMs,
                    endMillis = endMs,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "listDataPoints(${spec.dataType}) failed", e)
        }
        return out
    }

    // --- sleep (sessions with stage segments) ---

    private suspend fun fetchSleep(sinceMillis: Long): List<RemoteMeasurement> {
        val zone = ZoneId.systemDefault()
        val (start, end) = window(MAX_RANGE_DEFAULT, sinceMillis, zone)
        val filter = "sleep.interval.end_time >= \"${iso(start, zone)}\" AND sleep.interval.end_time < \"${iso(end, zone)}\""

        val out = mutableListOf<RemoteMeasurement>()
        try {
            forEachDataPoint("sleep", filter) { dp ->
                val sleep = dp["sleep"]?.jsonObject ?: return@forEachDataPoint
                val interval = sleep["interval"]?.jsonObject ?: return@forEachDataPoint
                val startMs = sampleMillis(interval["startTime"]) ?: return@forEachDataPoint
                val endMs = sampleMillis(interval["endTime"]) ?: return@forEachDataPoint
                val stages = (sleep["stages"] as? JsonArray).orEmpty().mapNotNull { st ->
                    val o = st.jsonObject
                    val s = sampleMillis(o["startTime"]) ?: return@mapNotNull null
                    val e = sampleMillis(o["endTime"]) ?: return@mapNotNull null
                    RemoteSleepStage(s, e, sleepStage(o["type"]?.jsonPrimitive?.content))
                }
                out += RemoteMeasurement(
                    clientRecordId = recordId(MeasurementType.SLEEP, startMs),
                    type = MeasurementType.SLEEP,
                    startMillis = startMs,
                    endMillis = endMs,
                    sleepStages = stages,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSleep failed", e)
        }
        return out
    }

    /** GET dataPoints for [dataType] with [filter], following pagination. */
    private suspend fun forEachDataPoint(dataType: String, filter: String, onPoint: (JsonObject) -> Unit) {
        var pageToken: String? = null
        var pages = 0
        do {
            val url = buildString {
                append("https://health.googleapis.com/v4/users/me/dataTypes/$dataType/dataPoints")
                append("?pageSize=1000&filter=").append(Uri.encode(filter))
                pageToken?.let { append("&pageToken=").append(Uri.encode(it)) }
            }
            val resp = NetworkClient.performRequest(url, "GET", headers(), null)
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: break
            (root["dataPoints"] as? JsonArray)?.forEach { onPoint(it.jsonObject) }
            pageToken = root["nextPageToken"]?.jsonPrimitive?.content?.ifBlank { null }
        } while (pageToken != null && ++pages < MAX_PAGES)
    }

    // --- helpers ---

    private fun recordId(type: MeasurementType, millis: Long) = "googlehealth:${type.name}:$millis"

    /** Inclusive start / exclusive end civil-day window, clamped to the type's max range. */
    private fun window(maxRangeDays: Int, sinceMillis: Long, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(zone)
        val sinceDate = if (sinceMillis > 0) {
            Instant.ofEpochMilli(sinceMillis).atZone(zone).toLocalDate()
        } else {
            today.minusDays(DEFAULT_LOOKBACK_DAYS)
        }
        val earliest = today.minusDays(maxRangeDays.toLong())
        val start = if (sinceDate.isBefore(earliest)) earliest else sinceDate
        return start to today.plusDays(1)
    }

    private fun dateJson(d: LocalDate): String =
        """{"year": ${d.year}, "month": ${d.monthValue}, "day": ${d.dayOfMonth}}"""

    private fun iso(d: LocalDate, zone: ZoneId): String =
        DateTimeFormatter.ISO_INSTANT.format(d.atStartOfDay(zone).toInstant())

    /** Convert a `{year,month,day}` object to epoch millis at local midnight. */
    private fun dateMillis(date: JsonObject?, zone: ZoneId): Long? {
        date ?: return null
        val year = date["year"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val month = date["month"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val day = date["day"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        return LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Parse a google-datetime (RFC 3339) instant to epoch millis. */
    private fun sampleMillis(physicalTime: JsonElement?): Long? {
        val s = (physicalTime as? JsonPrimitive)?.content?.ifBlank { null } ?: return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { null }
        }
    }

    /** Map a Google Health sleep stage enum to a Health Connect STAGE_TYPE_* value. */
    private fun sleepStage(google: String?): Int = when (google) {
        "AWAKE" -> SleepSessionRecord.STAGE_TYPE_AWAKE
        "LIGHT" -> SleepSessionRecord.STAGE_TYPE_LIGHT
        "DEEP" -> SleepSessionRecord.STAGE_TYPE_DEEP
        "REM" -> SleepSessionRecord.STAGE_TYPE_REM
        "ASLEEP" -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        "RESTLESS" -> SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
        else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
    }

    private fun JsonObject.nestedObject(vararg path: String): JsonObject? {
        var cur: JsonElement = this
        for (key in path) cur = (cur as? JsonObject)?.get(key) ?: return null
        return cur as? JsonObject
    }

    /** Read a numeric leaf at [path] (int64 arrives as a string, so parse via content). */
    private fun JsonObject.nestedDouble(vararg path: String): Double? {
        var cur: JsonElement = this
        for (key in path) cur = (cur as? JsonObject)?.get(key) ?: return null
        return (cur as? JsonPrimitive)?.content?.toDoubleOrNull()
    }

    private enum class Mode { ROLLUP, LIST_SAMPLE, LIST_DAILY, LIST_SESSION }

    private class Spec(
        /** Data type path segment, kebab-case per the v4 API. */
        val dataType: String,
        val type: MeasurementType,
        val mode: Mode,
        /** camelCase field holding the value, under a rollup point or DataPoint. */
        val jsonField: String,
        /** Keys from [jsonField] down to the numeric leaf. */
        val valuePath: Array<String>,
        /** snake_case field used in list-mode time-range filters. */
        val filterField: String = "",
        /** Server-side max civil-day range for this type/method. */
        val maxRangeDays: Int = MAX_RANGE_DEFAULT,
        /** Multiplier applied to the raw value (unit conversion). */
        val scale: Double = 1.0,
    )

    companion object {
        private const val TAG = "GoogleHealthClient"
        private const val DEFAULT_LOOKBACK_DAYS = 30L
        private const val MAX_PAGES = 50
        private const val MAX_RANGE_DEFAULT = 90
        private const val MAX_RANGE_14 = 14 // heart-rate & total-calories are capped at 14 days
        private const val DAY_MILLIS = 86_400_000L
        private const val GRAMS_PER_KG = 1000.0
        private const val MM_PER_METER = 1000.0
        private const val ML_PER_LITER = 1000.0

        private val SPECS = listOf(
            // Activity — dailyRollUp aggregates over each civil day.
            Spec("steps", MeasurementType.STEPS, Mode.ROLLUP, "steps", arrayOf("countSum")),
            Spec("distance", MeasurementType.DISTANCE, Mode.ROLLUP, "distance", arrayOf("millimetersSum"), scale = 1.0 / MM_PER_METER),
            Spec("floors", MeasurementType.FLOORS, Mode.ROLLUP, "floors", arrayOf("countSum")),
            Spec("active-energy-burned", MeasurementType.ACTIVE_CALORIES, Mode.ROLLUP, "activeEnergyBurned", arrayOf("kcalSum")),
            Spec("total-calories", MeasurementType.TOTAL_CALORIES, Mode.ROLLUP, "totalCalories", arrayOf("kcalSum"), maxRangeDays = MAX_RANGE_14),

            // Vitals — heart rate rolls up; the rest are samples / daily summaries.
            Spec("heart-rate", MeasurementType.HEART_RATE, Mode.ROLLUP, "heartRate", arrayOf("beatsPerMinuteAvg"), maxRangeDays = MAX_RANGE_14),
            Spec("daily-resting-heart-rate", MeasurementType.RESTING_HEART_RATE, Mode.LIST_DAILY, "dailyRestingHeartRate", arrayOf("beatsPerMinute"), filterField = "daily_resting_heart_rate"),
            Spec("daily-oxygen-saturation", MeasurementType.OXYGEN_SATURATION, Mode.LIST_DAILY, "dailyOxygenSaturation", arrayOf("averagePercentage"), filterField = "daily_oxygen_saturation"),
            Spec("daily-respiratory-rate", MeasurementType.RESPIRATORY_RATE, Mode.LIST_DAILY, "dailyRespiratoryRate", arrayOf("breathsPerMinute"), filterField = "daily_respiratory_rate"),
            Spec("heart-rate-variability", MeasurementType.HEART_RATE_VARIABILITY, Mode.LIST_SAMPLE, "heartRateVariability", arrayOf("rootMeanSquareOfSuccessiveDifferencesMilliseconds"), filterField = "heart_rate_variability"),
            Spec("blood-glucose", MeasurementType.BLOOD_GLUCOSE, Mode.LIST_SAMPLE, "bloodGlucose", arrayOf("bloodGlucoseMilligramsPerDeciliter"), filterField = "blood_glucose"),
            Spec("core-body-temperature", MeasurementType.BODY_TEMPERATURE, Mode.LIST_SAMPLE, "coreBodyTemperature", arrayOf("temperatureCelsius"), filterField = "core_body_temperature"),
            Spec("vo2-max", MeasurementType.VO2_MAX, Mode.LIST_SAMPLE, "vo2Max", arrayOf("vo2Max"), filterField = "vo2_max"),

            // Body composition — samples with real timestamps.
            Spec("weight", MeasurementType.WEIGHT, Mode.LIST_SAMPLE, "weight", arrayOf("weightGrams"), filterField = "weight", scale = 1.0 / GRAMS_PER_KG),
            Spec("height", MeasurementType.HEIGHT, Mode.LIST_SAMPLE, "height", arrayOf("heightMillimeters"), filterField = "height", scale = 1.0 / MM_PER_METER),
            Spec("body-fat", MeasurementType.BODY_FAT, Mode.LIST_SAMPLE, "bodyFat", arrayOf("percentage"), filterField = "body_fat"),

            // Lifestyle — hydration is a session with a volume.
            Spec("hydration-log", MeasurementType.HYDRATION, Mode.LIST_SESSION, "hydrationLog", arrayOf("amountConsumed", "milliliters"), filterField = "hydration_log", scale = 1.0 / ML_PER_LITER),
        )
    }
}
