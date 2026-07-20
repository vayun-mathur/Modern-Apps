package com.vayunmathur.everysync.remote

import android.net.Uri
import android.util.Log
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import com.vayunmathur.everysync.model.RemoteNutrition
import com.vayunmathur.everysync.model.RemoteSleepStage
import com.vayunmathur.library.network.NetworkClient
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 *   (steps, distance, floors, elevation, calories, heart rate).
 * - [Mode.LIST_SAMPLE] — instantaneous samples at their real timestamps (weight,
 *   height, body fat, glucose, temperature, VO2 max, HRV).
 * - [Mode.LIST_DAILY] — per-day summaries (resting HR, oxygen saturation,
 *   respiratory rate).
 * - [Mode.LIST_SESSION] — sessions with a scalar value (hydration).
 * - Sleep, exercise and nutrition are fetched separately (they carry extra data).
 *
 * Every request is chunked to the API's 14–90 day range limit; idempotent
 * clientRecordIds make re-pulling overlapping windows a safe upsert.
 */
class GoogleHealthClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun headers() = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Content-Type" to "application/json",
    )

    /**
     * Fetch everything recorded in the half-open window `[fromMillis, toMillis)`,
     * chunked to the API range limit. Callers pull a small recent window each run
     * and one older backfill window at a time rather than the whole history at once.
     *
     * Independent requests (each type × each chunk, plus the session fetches) run
     * concurrently with bounded parallelism; pagination within a request stays
     * sequential since it depends on the previous page token.
     */
    suspend fun getMeasurements(fromMillis: Long, toMillis: Long): List<RemoteMeasurement> = coroutineScope {
        val zone = ZoneId.systemDefault()
        if (fromMillis >= toMillis) return@coroutineScope emptyList()
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val jobs = mutableListOf<Deferred<List<RemoteMeasurement>>>()
        for (spec in SPECS) {
            // High-frequency raw types would explode over a multi-year backfill, so
            // clamp how far back each type goes independently of the deep backfill.
            val specFrom = backfillFloor(fromMillis, spec.maxBackfillDays)
            if (specFrom >= toMillis) continue
            for ((start, end) in chunks(specFrom, toMillis, spec.maxRangeDays, zone)) {
                jobs += async {
                    semaphore.withPermit {
                        if (spec.mode == Mode.ROLLUP) dailyRollUp(spec, start, end, zone)
                        else listDataPoints(spec, start, end, zone)
                    }
                }
            }
        }
        for ((start, end) in chunks(fromMillis, toMillis, MAX_RANGE_DEFAULT, zone)) {
            jobs += async { semaphore.withPermit { fetchSleep(start, end, zone) } }
            jobs += async { semaphore.withPermit { fetchExercise(start, end, zone) } }
            jobs += async { semaphore.withPermit { fetchNutrition(start, end, zone) } }
        }
        jobs.awaitAll().flatten()
    }

    /** Never look back further than [maxBackfillDays] for a given type. */
    private fun backfillFloor(fromMillis: Long, maxBackfillDays: Int): Long {
        if (maxBackfillDays >= UNBOUNDED_DAYS) return fromMillis
        return maxOf(fromMillis, System.currentTimeMillis() - maxBackfillDays.toLong() * DAY_MILLIS)
    }

    // --- dailyRollUp (steps, distance, floors, elevation, calories, heart rate) ---

    private suspend fun dailyRollUp(spec: Spec, start: LocalDate, end: LocalDate, zone: ZoneId): List<RemoteMeasurement> {
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
            if (!resp.isSuccess) {
                Log.e(TAG, "dailyRollUp ${spec.dataType} HTTP ${resp.status}: ${resp.body.take(500)}")
                return out
            }
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

    private suspend fun listDataPoints(spec: Spec, start: LocalDate, end: LocalDate, zone: ZoneId): List<RemoteMeasurement> {
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

    private suspend fun fetchSleep(start: LocalDate, end: LocalDate, zone: ZoneId): List<RemoteMeasurement> {
        val filter = "sleep.interval.end_time >= \"${iso(start, zone)}\" AND sleep.interval.end_time < \"${iso(end, zone)}\""
        val out = mutableListOf<RemoteMeasurement>()
        try {
            forEachDataPoint("sleep", filter, SESSION_PAGE_SIZE) { dp ->
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

    // --- exercise (workout sessions) ---

    private suspend fun fetchExercise(start: LocalDate, end: LocalDate, zone: ZoneId): List<RemoteMeasurement> {
        val filter = "exercise.interval.civil_start_time >= \"$start\" AND exercise.interval.civil_start_time < \"$end\""
        val out = mutableListOf<RemoteMeasurement>()
        try {
            forEachDataPoint("exercise", filter, SESSION_PAGE_SIZE) { dp ->
                val ex = dp["exercise"]?.jsonObject ?: return@forEachDataPoint
                val interval = ex["interval"]?.jsonObject ?: return@forEachDataPoint
                val startMs = sampleMillis(interval["startTime"]) ?: return@forEachDataPoint
                val endMs = sampleMillis(interval["endTime"]) ?: return@forEachDataPoint
                out += RemoteMeasurement(
                    clientRecordId = recordId(MeasurementType.EXERCISE, startMs),
                    type = MeasurementType.EXERCISE,
                    startMillis = startMs,
                    endMillis = endMs,
                    exerciseType = exerciseType(ex["exerciseType"]?.jsonPrimitive?.content),
                    title = ex["displayName"]?.jsonPrimitive?.content,
                    notes = ex["notes"]?.jsonPrimitive?.content,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchExercise failed", e)
        }
        return out
    }

    // --- nutrition (logged food) ---

    private suspend fun fetchNutrition(start: LocalDate, end: LocalDate, zone: ZoneId): List<RemoteMeasurement> {
        val filter = "nutrition_log.interval.civil_start_time >= \"$start\" AND nutrition_log.interval.civil_start_time < \"$end\""
        val out = mutableListOf<RemoteMeasurement>()
        try {
            forEachDataPoint("nutrition-log", filter) { dp ->
                val nl = dp["nutritionLog"]?.jsonObject ?: return@forEachDataPoint
                val interval = nl["interval"]?.jsonObject ?: return@forEachDataPoint
                val startMs = sampleMillis(interval["startTime"]) ?: return@forEachDataPoint
                val endMs = sampleMillis(interval["endTime"]) ?: startMs
                out += RemoteMeasurement(
                    clientRecordId = recordId(MeasurementType.NUTRITION, startMs),
                    type = MeasurementType.NUTRITION,
                    startMillis = startMs,
                    endMillis = endMs,
                    title = nl["foodDisplayName"]?.jsonPrimitive?.content,
                    nutrition = parseNutrition(nl),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchNutrition failed", e)
        }
        return out
    }

    private fun parseNutrition(nl: JsonObject): RemoteNutrition {
        val grams = mutableMapOf<String, Double>()
        nl.nestedDouble("totalFat", "grams")?.let { grams["TOTAL_FAT"] = it }
        nl.nestedDouble("totalCarbohydrate", "grams")?.let { grams["TOTAL_CARBOHYDRATE"] = it }
        (nl["nutrients"] as? JsonArray)?.forEach { el ->
            val o = el.jsonObject
            val name = o["nutrient"]?.jsonPrimitive?.content ?: return@forEach
            val g = o.nestedDouble("quantity", "grams") ?: return@forEach
            grams[name] = g
        }
        return RemoteNutrition(energyKcal = nl.nestedDouble("energy", "kcal"), nutrientGrams = grams)
    }

    /** GET dataPoints for [dataType] with [filter], following pagination. */
    private suspend fun forEachDataPoint(dataType: String, filter: String, pageSize: Int = LIST_PAGE_SIZE, onPoint: (JsonObject) -> Unit) {
        var pageToken: String? = null
        var pages = 0
        var total = 0
        do {
            val url = buildString {
                append("https://health.googleapis.com/v4/users/me/dataTypes/$dataType/dataPoints")
                append("?pageSize=").append(pageSize).append("&filter=").append(Uri.encode(filter))
                pageToken?.let { append("&pageToken=").append(Uri.encode(it)) }
            }
            val resp = NetworkClient.performRequest(url, "GET", headers(), null)
            if (!resp.isSuccess) {
                Log.e(TAG, "list $dataType HTTP ${resp.status}: ${resp.body.take(500)} (filter=$filter)")
                break
            }
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: break
            val points = root["dataPoints"] as? JsonArray
            points?.forEach { onPoint(it.jsonObject); total++ }
            pageToken = root["nextPageToken"]?.jsonPrimitive?.content?.ifBlank { null }
        } while (pageToken != null && ++pages < MAX_PAGES)
        Log.i(TAG, "list $dataType -> $total points")
    }

    // --- helpers ---

    private fun recordId(type: MeasurementType, millis: Long) = "googlehealth:${type.name}:$millis"

    /** Split `[fromMillis, toMillis)` into inclusive-start/exclusive-end windows of [chunkDays]. */
    private fun chunks(fromMillis: Long, toMillis: Long, chunkDays: Int, zone: ZoneId): List<Pair<LocalDate, LocalDate>> {
        val today = LocalDate.now(zone)
        // Round the end up a day so the final partial day is covered; never past today.
        val endExclusive = minOf(
            Instant.ofEpochMilli(toMillis.coerceAtLeast(0)).atZone(zone).toLocalDate().plusDays(1),
            today.plusDays(1),
        )
        var start = Instant.ofEpochMilli(fromMillis.coerceAtLeast(0)).atZone(zone).toLocalDate()
        if (start.isAfter(today)) start = today
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        var s = start
        while (s.isBefore(endExclusive)) {
            val e = minOf(s.plusDays(chunkDays.toLong()), endExclusive)
            out += s to e
            s = e
        }
        return out
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

    /** Map a Google Health exercise enum to a Health Connect EXERCISE_TYPE_* value. */
    private fun exerciseType(google: String?): Int = when (google) {
        "BADMINTON" -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        "BASEBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL
        "BASKETBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        "BIKING", "OUTDOOR_BIKE" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "STATIONARY_BIKE", "ASSAULT_BIKE", "SPINNING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        "BOOTCAMP" -> ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP
        "BOXING" -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
        "CALISTHENICS" -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
        "CRICKET" -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
        "DANCING", "BALLET", "BALLROOM_DANCE", "HIP_HOP", "JAZZ_DANCE", "MODERN_DANCE", "TANGO", "ZUMBA" ->
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        "ELLIPTICAL" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        "EXERCISE_CLASS", "BARRE_CLASS", "CARDIO_SCULPT" -> ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS
        "FENCING" -> ExerciseSessionRecord.EXERCISE_TYPE_FENCING
        "FOOTBALL_AMERICAN" -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN
        "FOOTBALL_AUSTRALIAN" -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN
        "GOLF" -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
        "GUIDED_BREATHING", "MEDITATE" -> ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING
        "GYMNASTICS" -> ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS
        "HANDBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL
        "HIIT", "INTERVAL_WORKOUT", "TABATA_WORKOUT", "CIRCUIT_TRAINING", "CROSSFIT" ->
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        "HIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "HOCKEY", "FIELD_HOCKEY" -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY
        "ICE_SKATING", "SPEED_SKATING" -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING
        "MARTIAL_ARTS", "KARATE", "TAEKWONDO", "MUAY_THAI", "JIU_JITSU", "KICKBOXING" ->
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
        "PADDLEBOARDING", "KAYAKING", "CANOEING", "ROWING" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
        "ROWING_MACHINE" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        "PARAGLIDING" -> ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING
        "PILATES" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        "RACQUETBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL
        "ROCK_CLIMBING", "CLIMBING", "INDOOR_CLIMBING" -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
        "RUGBY" -> ExerciseSessionRecord.EXERCISE_TYPE_RUGBY
        "RUNNING", "TRAIL_RUN", "INCLINE_RUN" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "TREADMILL" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        "SAILING", "FOILING" -> ExerciseSessionRecord.EXERCISE_TYPE_SAILING
        "SCUBA_DIVING", "DIVING" -> ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING
        "SKATING", "ROLLER_SKATING", "ROLLERBLADING" -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING
        "SKIING", "CROSS_COUNTRY_SKI" -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
        "SNOWBOARDING" -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING
        "SNOWSHOEING" -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING
        "SOCCER" -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        "SOFTBALL" -> ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL
        "SQUASH" -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH
        "STAIRCLIMBER", "STAIR_CLIMBING" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING
        "STEP_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
        "STRENGTH_TRAINING", "POWERLIFTING", "FUNCTIONAL_STRENGTH_TRAINING", "FREE_WEIGHTS", "WEIGHT_MACHINES", "CORE_TRAINING", "RESISTANCE_BANDS" ->
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "STRETCHING", "TAI_CHI" -> ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING
        "SURFING" -> ExerciseSessionRecord.EXERCISE_TYPE_SURFING
        "SWIMMING_OPEN_WATER" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
        "SWIMMING_POOL", "SWIMMING", "SYNCHRONIZED_SWIMMING" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        "TABLE_TENNIS" -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        "TENNIS", "PADEL", "PICKELBALL", "RACKET_SPORTS" -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        "VOLLEYBALL", "VOLLEYBALL_BEACH" -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        "WALKING", "POWER_WALKING", "NORDIC_WALKING", "STROLLER_WALK", "WALK_WITH_WEIGHTS", "RUCKING", "TREADMILL_WALK", "INCLINE_WALK" ->
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "WATER_POLO" -> ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO
        "WEIGHTLIFTING", "WEIGHTS" -> ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
        "WHEELCHAIR" -> ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR
        "YOGA", "YOGA_BIKRAM", "YOGA_HATHA", "YOGA_POWER", "YOGA_VINYASA" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
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
        /** Cap on how far back to backfill (raw high-frequency types stay bounded). */
        val maxBackfillDays: Int = UNBOUNDED_DAYS,
        /** Multiplier applied to the raw value (unit conversion). */
        val scale: Double = 1.0,
    )

    companion object {
        private const val TAG = "GoogleHealthClient"
        private const val MAX_PAGES = 100
        private const val MAX_CONCURRENCY = 6 // parallel in-flight requests
        private const val LIST_PAGE_SIZE = 10000 // API max for dataPoints
        private const val SESSION_PAGE_SIZE = 25 // API max for exercise + sleep
        private const val MAX_RANGE_DEFAULT = 90
        private const val MAX_RANGE_14 = 14 // heart-rate & total-calories are capped at 14 days
        private const val UNBOUNDED_DAYS = 1_000_000 // effectively no per-type backfill cap
        private const val DAY_MILLIS = 86_400_000L
        private const val GRAMS_PER_KG = 1000.0
        private const val MM_PER_METER = 1000.0
        private const val ML_PER_LITER = 1000.0

        private val SPECS = listOf(
            // Activity — raw intervals (the API's native ~per-minute buckets) for full resolution.
            Spec("steps", MeasurementType.STEPS, Mode.LIST_SESSION, "steps", arrayOf("count"), filterField = "steps"),
            Spec("distance", MeasurementType.DISTANCE, Mode.LIST_SESSION, "distance", arrayOf("millimeters"), filterField = "distance", scale = 1.0 / MM_PER_METER),
            Spec("altitude", MeasurementType.ELEVATION, Mode.LIST_SESSION, "altitude", arrayOf("gainMillimeters"), filterField = "altitude", scale = 1.0 / MM_PER_METER),
            Spec("active-energy-burned", MeasurementType.ACTIVE_CALORIES, Mode.LIST_SESSION, "activeEnergyBurned", arrayOf("kcal"), filterField = "active_energy_burned"),
            // floors is not list-able and total-calories is rollup-only, so both stay daily rollups.
            Spec("floors", MeasurementType.FLOORS, Mode.ROLLUP, "floors", arrayOf("countSum")),
            Spec("total-calories", MeasurementType.TOTAL_CALORIES, Mode.ROLLUP, "totalCalories", arrayOf("kcalSum"), maxRangeDays = MAX_RANGE_14),

            // Vitals — raw samples where the API provides them.
            Spec("heart-rate", MeasurementType.HEART_RATE, Mode.LIST_SAMPLE, "heartRate", arrayOf("beatsPerMinute"), filterField = "heart_rate", maxRangeDays = MAX_RANGE_14),
            Spec("oxygen-saturation", MeasurementType.OXYGEN_SATURATION, Mode.LIST_SAMPLE, "oxygenSaturation", arrayOf("percentage"), filterField = "oxygen_saturation"),
            Spec("daily-resting-heart-rate", MeasurementType.RESTING_HEART_RATE, Mode.LIST_DAILY, "dailyRestingHeartRate", arrayOf("beatsPerMinute"), filterField = "daily_resting_heart_rate"),
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
